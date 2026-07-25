package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.demand.AmbiguousSourceDemandProposal;
import org.zipp.ai.application.turn.demand.Confidence;
import org.zipp.ai.application.turn.demand.CurrentInstructionSpan;
import org.zipp.ai.application.turn.demand.NoSourceDemandProposal;
import org.zipp.ai.application.turn.demand.ProposalEvidence;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandInput;
import org.zipp.ai.application.turn.demand.RestrictedSourceDemandPromptRenderer;
import org.zipp.ai.application.turn.demand.SourceDemandInterpreterPort;
import org.zipp.ai.application.turn.demand.SourceDemandInterpreterUnavailable;
import org.zipp.ai.application.turn.demand.SourceDemandKind;
import org.zipp.ai.application.turn.demand.SourceDemandProposal;
import org.zipp.ai.application.turn.demand.SourceDemandProposalOutcome;
import org.zipp.ai.application.turn.demand.SourceDemandProposalReady;
import org.zipp.ai.application.turn.demand.TypedSourceDemandProposal;
import org.zipp.ai.domain.agent.service.IChatService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Concrete restricted-input demand adapter; output is a proposal, never a source authorization. */
@Component
public final class ChatSourceDemandInterpreterAdapter implements SourceDemandInterpreterPort {

    private static final Set<String> FIELDS = Set.of(
            "demandKind", "confidence", "safeReason", "attachmentRefs", "relevanceQuery", "spans");
    private static final Set<String> SPAN_FIELDS = Set.of("start", "end", "digest");
    private final ToolFreeChatModelInvoker model;
    private final RestrictedSourceDemandPromptRenderer renderer = new RestrictedSourceDemandPromptRenderer();

    public ChatSourceDemandInterpreterAdapter(
            IChatService chat,
            @Value("${zipp.turn.v2.source-demand-agent-id:300024}") String agentId
    ) {
        this(new ToolFreeChatModelInvoker(chat, agentId, "v2-source-demand-interpreter"));
    }

    ChatSourceDemandInterpreterAdapter(ToolFreeChatModelInvoker model) {
        this.model = model;
    }

    @Override
    public SourceDemandProposalOutcome interpret(RestrictedSourceDemandInput input) {
        if (input == null) {
            return new SourceDemandInterpreterUnavailable("V2_SOURCE_DEMAND_INPUT_INVALID");
        }
        final String output;
        try {
            output = model.invoke(renderer.render(input));
        } catch (RuntimeException exception) {
            return new SourceDemandInterpreterUnavailable("V2_SOURCE_DEMAND_MODEL_UNAVAILABLE");
        }
        try {
            return new SourceDemandProposalReady(parse(output));
        } catch (RuntimeException exception) {
            return new SourceDemandInterpreterUnavailable("V2_SOURCE_DEMAND_OUTPUT_INVALID");
        }
    }

    private SourceDemandProposal parse(String output) {
        JSONObject root = JSON.parseObject(output);
        if (root == null || !root.keySet().equals(FIELDS)) {
            throw new IllegalArgumentException("source demand output fields are not exact");
        }
        String kind = text(root, "demandKind", 64);
        Confidence confidence = Confidence.valueOf(text(root, "confidence", 32));
        String safeReason = text(root, "safeReason", 512);
        List<CurrentInstructionSpan> spans = spans(root.getJSONArray("spans"));
        Optional<String> relevanceQuery = optionalText(root, "relevanceQuery", 1_000);
        List<String> attachmentRefs = strings(root.getJSONArray("attachmentRefs"));
        ProposalEvidence evidence = new ProposalEvidence(spans, confidence, relevanceQuery);
        return switch (kind) {
            case "NO_SOURCE" -> new NoSourceDemandProposal(evidence, safeReason);
            case "AMBIGUOUS" -> new AmbiguousSourceDemandProposal(evidence, safeReason);
            case "CURRENT_MESSAGE_ATTACHMENTS_REQUIRED" -> new TypedSourceDemandProposal(
                    SourceDemandKind.CURRENT_MESSAGE_ATTACHMENTS_REQUIRED,
                    attachmentRefs, relevanceQuery.orElse(null), evidence, safeReason);
            case "OPTIONAL_DISCOVERY" -> new TypedSourceDemandProposal(
                    SourceDemandKind.OPTIONAL_DISCOVERY,
                    attachmentRefs, relevanceQuery.orElse(null), evidence, safeReason);
            default -> throw new IllegalArgumentException("unknown source demand kind");
        };
    }

    private List<CurrentInstructionSpan> spans(JSONArray values) {
        if (values == null || values.isEmpty() || values.size() > 8) {
            throw new IllegalArgumentException("source demand spans are invalid");
        }
        List<CurrentInstructionSpan> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof JSONObject span) || !span.keySet().equals(SPAN_FIELDS)) {
                throw new IllegalArgumentException("source demand span fields are not exact");
            }
            Integer start = span.getInteger("start");
            Integer end = span.getInteger("end");
            if (start == null || end == null) {
                throw new IllegalArgumentException("source demand span range is invalid");
            }
            result.add(new CurrentInstructionSpan(start, end, text(span, "digest", 128)));
        }
        return List.copyOf(result);
    }

    private List<String> strings(JSONArray values) {
        if (values == null || values.size() > 16) {
            throw new IllegalArgumentException("source demand attachment refs are invalid");
        }
        List<String> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank() || text.length() > 256
                    || !unique.add(text)) {
                throw new IllegalArgumentException("source demand attachment refs are invalid");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private Optional<String> optionalText(JSONObject root, String field, int limit) {
        Object raw = root.get(field);
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof String value) || value.length() > limit) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        String normalized = value.trim();
        return normalized.isBlank() ? Optional.empty() : Optional.of(normalized);
    }

    private String text(JSONObject root, String field, int limit) {
        String value = root.getString(field);
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }
}
