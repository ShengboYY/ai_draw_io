package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.agent.CallDiagramTool;
import org.zipp.ai.application.turn.agent.CreateDraftRequest;
import org.zipp.ai.application.turn.agent.DiagramAgentAction;
import org.zipp.ai.application.turn.agent.DiagramAgentDecisionPort;
import org.zipp.ai.application.turn.agent.DiagramAgentObservation;
import org.zipp.ai.application.turn.agent.DraftCellMutation;
import org.zipp.ai.application.turn.agent.DraftInspectionScope;
import org.zipp.ai.application.turn.agent.DraftMutationType;
import org.zipp.ai.application.turn.agent.DraftRef;
import org.zipp.ai.application.turn.agent.InspectDraftRequest;
import org.zipp.ai.application.turn.agent.PatchDraftRequest;
import org.zipp.ai.application.turn.agent.ReviewDraftRequest;
import org.zipp.ai.application.turn.agent.SubmitDiagramCandidate;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Strict JSON decision adapter over the same isolated tool-free V2 worker. */
@Component
public final class ChatDiagramAgentDecisionAdapter implements DiagramAgentDecisionPort {

    private static final Set<String> CALL_FIELDS = Set.of("action", "toolName", "arguments");
    private static final Set<String> SUBMIT_FIELDS =
            Set.of("action", "draftRef", "expectedDigest", "assistantMessage");
    private static final int MAX_XML_LENGTH = 4_000_000;
    private final ToolFreeChatModelInvoker model;
    private final PlainGenerationPromptRenderer renderer = new PlainGenerationPromptRenderer();

    @Autowired
    public ChatDiagramAgentDecisionAdapter(
            IChatService chat,
            @Value("${zipp.turn.v2.plain-agent-id:300025}") String agentId
    ) {
        this(new ToolFreeChatModelInvoker(chat, agentId, "v2-plain-agent-decision"));
    }

    ChatDiagramAgentDecisionAdapter(ToolFreeChatModelInvoker model) {
        this.model = model;
    }

    @Override
    public DiagramAgentAction decide(
            DiagramAgentObservation observation,
            CancellationSignal cancellation
    ) {
        if (observation == null) {
            throw new IllegalArgumentException("diagram agent observation is required");
        }
        final String output;
        try {
            output = model.invoke(
                    observation.modelInputBinding(),
                    renderer.render(observation),
                    cancellation);
        } catch (CancellationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("V2_PLAIN_AGENT_MODEL_UNAVAILABLE", exception);
        }
        try {
            return parse(output);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("V2_PLAIN_AGENT_MODEL_OUTPUT_INVALID", exception);
        }
    }

    DiagramAgentAction parse(String output) {
        JSONObject root = JSON.parseObject(output);
        if (root == null) {
            throw new IllegalArgumentException("agent action is missing");
        }
        String action = bounded(root, "action", 64);
        if ("SUBMIT_CANDIDATE".equals(action)) {
            requireFields(root, SUBMIT_FIELDS);
            return new SubmitDiagramCandidate(
                    new DraftRef(bounded(root, "draftRef", 128)),
                    bounded(root, "expectedDigest", 80),
                    bounded(root, "assistantMessage", 16_000));
        }
        if (!"CALL_TOOL".equals(action)) {
            throw new IllegalArgumentException("agent action is unsupported");
        }
        requireFields(root, CALL_FIELDS);
        String toolName = bounded(root, "toolName", 64);
        JSONObject arguments = root.getJSONObject("arguments");
        if (arguments == null) {
            throw new IllegalArgumentException("tool arguments are missing");
        }
        return new CallDiagramTool(switch (toolName) {
            case "create_draft" -> parseCreate(arguments);
            case "patch_draft" -> parsePatch(arguments);
            case "inspect_draft" -> parseInspect(arguments);
            case "review_draft" -> parseReview(arguments);
            default -> throw new IllegalArgumentException("tool name is unsupported");
        });
    }

    private CreateDraftRequest parseCreate(JSONObject arguments) {
        requireFields(arguments, Set.of("canvasXml"));
        return new CreateDraftRequest(bounded(arguments, "canvasXml", MAX_XML_LENGTH));
    }

    private PatchDraftRequest parsePatch(JSONObject arguments) {
        requireFields(arguments, Set.of("draftRef", "expectedDigest", "mutations"));
        JSONArray array = arguments.getJSONArray("mutations");
        if (array == null || array.isEmpty() || array.size() > 64) {
            throw new IllegalArgumentException("patch mutations are invalid");
        }
        List<DraftCellMutation> mutations = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JSONObject value = array.getJSONObject(index);
            requireFields(value, Set.of("type", "cellId", "cellXml"));
            DraftMutationType type = DraftMutationType.valueOf(bounded(value, "type", 16));
            mutations.add(new DraftCellMutation(
                    type,
                    bounded(value, "cellId", 255),
                    optionalBounded(value, "cellXml", MAX_XML_LENGTH)));
        }
        return new PatchDraftRequest(
                new DraftRef(bounded(arguments, "draftRef", 128)),
                bounded(arguments, "expectedDigest", 80),
                mutations);
    }

    private InspectDraftRequest parseInspect(JSONObject arguments) {
        requireFields(arguments, Set.of("draftRef", "scope", "cellIds", "query"));
        JSONArray array = arguments.getJSONArray("cellIds");
        if (array == null || array.size() > 64) {
            throw new IllegalArgumentException("inspect cellIds are invalid");
        }
        List<String> cellIds = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            String cellId = array.getString(index);
            if (cellId == null || cellId.isBlank() || cellId.length() > 255) {
                throw new IllegalArgumentException("inspect cell id is invalid");
            }
            cellIds.add(cellId.trim());
        }
        return new InspectDraftRequest(
                new DraftRef(bounded(arguments, "draftRef", 128)),
                DraftInspectionScope.valueOf(bounded(arguments, "scope", 32)),
                cellIds,
                optionalBounded(arguments, "query", 1_000));
    }

    private ReviewDraftRequest parseReview(JSONObject arguments) {
        requireFields(arguments, Set.of("draftRef", "expectedDigest"));
        return new ReviewDraftRequest(
                new DraftRef(bounded(arguments, "draftRef", 128)),
                bounded(arguments, "expectedDigest", 80));
    }

    private void requireFields(JSONObject value, Set<String> expected) {
        if (value == null || !value.keySet().equals(expected)) {
            throw new IllegalArgumentException("JSON fields are not exact");
        }
    }

    private String bounded(JSONObject value, String field, int limit) {
        String result = value.getString(field);
        if (result == null || result.isBlank() || result.length() > limit) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result.trim();
    }

    private String optionalBounded(JSONObject value, String field, int limit) {
        String result = value.getString(field);
        if (result == null || result.length() > limit) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result.trim();
    }
}
