package org.zipp.ai.trigger.http.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.chat.CustomApiConfigManager;
import org.zipp.ai.domain.citation.model.valobj.ClaimSupportVerdict;
import org.zipp.ai.domain.citation.port.ClaimSupportVerifierPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Bounded no-tool entailment call using the same configured model credentials as the drawing run. */
@Service
@ConditionalOnProperty(name = {"app.material-rag.enabled", "app.material-lifecycle.enabled"}, havingValue = "true")
public class ConfiguredModelClaimSupportVerifier implements ClaimSupportVerifierPort {
    private static final String VERIFIER_AGENT_ID = "300019";
    private final IChatService chatService;
    private final ExecutorService executor;
    private final Map<String, RunModelContext> contexts = new ConcurrentHashMap<>();

    public ConfiguredModelClaimSupportVerifier(IChatService chatService,
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
    public List<Result> verify(List<Request> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > 8) return List.of();
        String runId = requests.get(0).runId();
        if (runId == null || requests.stream().anyMatch(request -> !runId.equals(request.runId()))) return List.of();
        RunModelContext context = contexts.get(runId);
        if (context == null) return List.of();
        Future<List<Result>> future = executor.submit(() -> invoke(context, requests));
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            future.cancel(true);
            return List.of();
        }
    }

    private List<Result> invoke(RunModelContext context, List<Request> requests) {
        String sessionId = chatService.createSession(VERIFIER_AGENT_ID, context.userId());
        try {
            if (context.config() != null && context.config().isCustomModelSelected()) {
                CustomApiConfigManager.setConfig(sessionId, context.config());
            }
            JSONArray claims = new JSONArray();
            for (Request request : requests) {
                JSONObject claim = new JSONObject(new LinkedHashMap<>());
                claim.put("statementKey", request.statementKey());
                claim.put("statement", request.statementText());
                claim.put("anchors", request.anchors());
                claims.add(claim);
            }
            String prompt = "[Untrusted Claims]\n" + claims.toJSONString();
            String raw = String.join("", chatService.handleMessage(
                    VERIFIER_AGENT_ID, context.userId(), sessionId, prompt));
            return parse(raw, requests);
        } finally {
            CustomApiConfigManager.clearConfig(sessionId);
        }
    }

    private List<Result> parse(String raw, List<Request> requests) {
        JSONObject root = JSON.parseObject(raw);
        JSONArray values = root == null ? null : root.getJSONArray("results");
        if (values == null || values.size() != requests.size()) return List.of();
        Map<String, ClaimSupportVerdict> parsed = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            JSONObject value = values.getJSONObject(index);
            String key = value.getString("statementKey");
            ClaimSupportVerdict verdict = ClaimSupportVerdict.valueOf(value.getString("verdict"));
            if (parsed.putIfAbsent(key, verdict) != null) return List.of();
        }
        ArrayList<Result> results = new ArrayList<>();
        for (Request request : requests) {
            ClaimSupportVerdict verdict = parsed.get(request.statementKey());
            if (verdict == null) return List.of();
            results.add(new Result(request.statementKey(), verdict));
        }
        return List.copyOf(results);
    }

    private record RunModelContext(String userId, CustomApiConfigManager.CustomApiConfig config) { }
}
