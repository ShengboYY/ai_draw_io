package org.zipp.ai.trigger.http.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.chat.CustomApiConfigManager;
import org.zipp.ai.domain.citation.answer.*;
import org.zipp.ai.domain.citation.model.valobj.SupportAtom;
import org.zipp.ai.domain.citation.model.valobj.SupportAtomRole;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;

import java.util.*;
import java.util.concurrent.*;

/** Bounded no-tool answer call. The returned JSON remains untrusted until the domain Guard accepts it. */
@Service
@ConditionalOnProperty(name = {"app.material-rag.enabled", "app.material-lifecycle.enabled",
        "app.material-rag.evidence-answer-enabled"}, havingValue = "true")
public class ConfiguredModelEvidenceAnswerGenerator implements EvidenceAnswerGeneratorPort {
    private static final String ANSWER_AGENT_ID = "300020";
    private final IChatService chatService;
    private final ExecutorService executor;
    private final Map<String, RunModelContext> contexts = new ConcurrentHashMap<>();

    public ConfiguredModelEvidenceAnswerGenerator(IChatService chatService,
                                                  @Qualifier("materialRagIoExecutor") ExecutorService executor) {
        this.chatService = chatService;
        this.executor = executor;
    }

    public void register(String runId, String userId, CustomApiConfigManager.CustomApiConfig config) {
        if (runId != null && !runId.isBlank() && userId != null && !userId.isBlank()) {
            contexts.put(runId, new RunModelContext(userId, config));
        }
    }

    public void unregister(String runId) {
        if (runId != null) contexts.remove(runId);
    }

    @Override
    public AnswerProposal generate(GenerationCommand command) {
        RunModelContext context = contexts.get(command.runId());
        if (context == null) throw new IllegalStateException("EVIDENCE_ANSWER_MODEL_CONTEXT_MISSING");
        Future<AnswerProposal> future = executor.submit(() -> invoke(context, command));
        try {
            return future.get(8, TimeUnit.SECONDS);
        } catch (Exception failure) {
            future.cancel(true);
            throw new IllegalStateException("EVIDENCE_ANSWER_MODEL_UNAVAILABLE", failure);
        }
    }

    private AnswerProposal invoke(RunModelContext context, GenerationCommand command) {
        String sessionId = chatService.createSession(ANSWER_AGENT_ID, context.userId());
        try {
            if (context.config() != null && context.config().isCustomModelSelected()) {
                CustomApiConfigManager.setConfig(sessionId, context.config());
            }
            String raw = String.join("", chatService.handleMessage(
                    ANSWER_AGENT_ID, context.userId(), sessionId, prompt(command)));
            try {
                return parse(raw);
            } catch (RuntimeException malformed) {
                // One bounded no-tool repair is permitted; evidence and citation keys remain unchanged.
                String repaired = String.join("", chatService.handleMessage(ANSWER_AGENT_ID,
                        context.userId(), sessionId,
                        "Previous output violated the required JSON schema. Return one corrected JSON object only."));
                return parse(repaired);
            }
        } finally {
            CustomApiConfigManager.clearConfig(sessionId);
        }
    }

    private String prompt(GenerationCommand command) {
        JSONObject input = new JSONObject(new LinkedHashMap<>());
        input.put("question", command.question());
        input.put("targetContext", command.targetContext());
        input.put("conversationContext", command.conversationContext());
        JSONArray evidence = new JSONArray();
        for (EvidenceBundleItem item : command.evidence()) {
            JSONObject value = new JSONObject(new LinkedHashMap<>());
            value.put("citationKey", item.citationKey());
            value.put("sourceLabel", item.sourceLabel());
            value.put("page", item.pageNumber());
            value.put("modality", item.modality());
            value.put("supportRole", item.supportRole().name());
            value.put("origin", item.origin().name());
            value.put("boundedDisplayText", item.text());
            evidence.add(value);
        }
        input.put("evidence", evidence);
        return "[Untrusted Evidence Answer Input]\n" + input.toJSONString();
    }

    private AnswerProposal parse(String raw) {
        JSONObject root = JSON.parseObject(raw);
        if (root == null || root.getJSONArray("claims") == null) {
            throw new IllegalArgumentException("ANSWER_JSON_INVALID");
        }
        List<AnswerClaim> claims = new ArrayList<>();
        for (Object object : root.getJSONArray("claims")) {
            JSONObject value = (JSONObject) object;
            List<String> citationKeys = strings(value.getJSONArray("citationKeys"));
            List<SupportAtom> atoms = new ArrayList<>();
            JSONArray rawAtoms = value.getJSONArray("supportAtoms");
            if (rawAtoms != null) for (Object rawAtom : rawAtoms) {
                JSONObject atom = (JSONObject) rawAtom;
                atoms.add(new SupportAtom(atom.getString("atomKey"), atom.getString("citationKey"),
                        atom.getString("anchorText"), SupportAtomRole.valueOf(atom.getString("role"))));
            }
            claims.add(new AnswerClaim(value.getString("claimKey"), value.getString("statementText"),
                    citationKeys, AnswerSupportType.valueOf(value.getString("supportType")), atoms));
        }
        List<AnswerGap> gaps = new ArrayList<>();
        JSONArray rawGaps = root.getJSONArray("gaps");
        if (rawGaps != null) for (Object rawGap : rawGaps) {
            JSONObject value = (JSONObject) rawGap;
            gaps.add(new AnswerGap(value.getString("gapKey"), value.getString("facetKey"),
                    value.getString("reasonCode")));
        }
        List<AnswerConflict> conflicts = new ArrayList<>();
        JSONArray rawConflicts = root.getJSONArray("conflicts");
        if (rawConflicts != null) for (Object rawConflict : rawConflicts) {
            JSONObject value = (JSONObject) rawConflict;
            List<List<String>> groups = new ArrayList<>();
            JSONArray rawGroups = value.getJSONArray("citationGroups");
            if (rawGroups != null) for (Object rawGroup : rawGroups) groups.add(strings((JSONArray) rawGroup));
            conflicts.add(new AnswerConflict(value.getString("conflictKey"), value.getString("facetKey"),
                    groups, value.getString("reasonCode")));
        }
        return new AnswerProposal(claims, gaps, conflicts);
    }

    private List<String> strings(JSONArray values) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        for (Object value : values) if (value instanceof String text) result.add(text);
        return List.copyOf(result);
    }

    private record RunModelContext(String userId, CustomApiConfigManager.CustomApiConfig config) { }
}
