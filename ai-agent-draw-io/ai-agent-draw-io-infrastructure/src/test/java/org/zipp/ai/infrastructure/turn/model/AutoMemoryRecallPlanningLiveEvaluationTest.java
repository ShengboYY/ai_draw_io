package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.context.AutoMemoryContextQuery;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit DeepSeek gate over frozen synthetic recall-planning requests. */
@EnabledIfEnvironmentVariable(
        named = "AUTO_MEMORY_RECALL_PLANNING_EVALUATION_ENABLED",
        matches = "true")
class AutoMemoryRecallPlanningLiveEvaluationTest {
    private static final String DEVELOPMENT_SHA256 =
            "cc274217e5b29334226d1583b9b05c3f432a5152a226659cbb3512066baa0227";
    private static final String HOLDOUT_SHA256 =
            "01bd2a8694e53cb6c0bc59cbd3f8479ee4452ceed3ec4fe8bdec0176bce50e1b";
    private static final int MAX_TOKENS = 2_048;

    @Test
    void deepSeekRecallPlanningMeetsTheFrozenGate() throws Exception {
        Profile profile = profile();
        byte[] bytes = Files.readAllBytes(cohortPath(profile));
        assertEquals(profile.sha256(), sha256(bytes));
        JSONObject cohort = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        URI endpoint = URI.create(withoutTrailingSlash(environment(
                "AUTO_MEMORY_BASE_URL", "https://api.deepseek.com")) + "/"
                + withoutLeadingSlash(environment(
                "AUTO_MEMORY_COMPLETIONS_PATH", "v1/chat/completions")));
        String apiKey = requiredEnvironment("AUTO_MEMORY_API_KEY", "LLM_API_KEY_deepseek");
        String model = environment("AUTO_MEMORY_MODEL", "deepseek-v4-pro");

        List<CaseRun> runs = new ArrayList<>();
        for (Object raw : cohort.getJSONArray("cases")) {
            runs.add(execute(client, endpoint, apiKey, model, (JSONObject) raw));
        }
        Metrics metrics = metrics(runs);
        JSONObject gate = cohort.getJSONObject("qualityGate");
        boolean passed = metrics.caseAccuracy() >= gate.getDoubleValue("minimumCaseAccuracy")
                && metrics.multiIntentCoverage()
                >= gate.getDoubleValue("minimumMultiIntentCoverage")
                && metrics.singleIntentAbstention()
                >= gate.getDoubleValue("minimumSingleIntentAbstention")
                && metrics.protocolFailureRate()
                <= gate.getDoubleValue("maximumProtocolFailureRate");
        Path report = reportPath(cohort.getString("datasetVersion"), model);
        writeReport(report, cohort, profile, model, endpoint, metrics, passed, runs);

        assertTrue(!profile.enforceGate() || passed,
                () -> "V1.8 recall planning evaluation failed: " + metrics
                        + "; report=" + report.toAbsolutePath());
    }

    private CaseRun execute(
            HttpClient client,
            URI endpoint,
            String apiKey,
            String model,
            JSONObject testCase
    ) throws Exception {
        String caseId = testCase.getString("id");
        TurnKey turn = new TurnKey("planning-eval-owner", "planning-eval", caseId);
        String userTurn = testCase.getString("userTurn");
        String contextDigest = ModelInputBinding.digestOf("recall-planning-eval", userTurn);
        AutoMemoryContextQuery query = new AutoMemoryContextQuery(
                turn,
                null,
                userTurn,
                ModelInputBinding.bound(
                        turn, contextDigest, ModelInputBinding.digestOf(userTurn, contextDigest)));
        long started = System.nanoTime();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(60))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                JSON.toJSONString(request(model, query)), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long latencyMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        if (response.statusCode() != 200) {
            return new CaseRun(caseId, expectedCount(testCase), List.of(),
                    false, false, latencyMillis, "HTTP_" + response.statusCode(), null);
        }
        JSONObject body = JSON.parseObject(response.body());
        JSONObject choice = body.getJSONArray("choices").getJSONObject(0);
        String output = choice.getJSONObject("message").getString("content");
        try {
            List<String> queries = AutoMemoryRecallPlanningProtocol.parse(output);
            return new CaseRun(
                    caseId,
                    expectedCount(testCase),
                    queries,
                    true,
                    matches(testCase, queries),
                    latencyMillis,
                    null,
                    body.getJSONObject("usage"));
        } catch (RuntimeException failure) {
            return new CaseRun(caseId, expectedCount(testCase), List.of(),
                    false, false, latencyMillis, failure.getClass().getSimpleName(),
                    body.getJSONObject("usage"));
        }
    }

    private JSONObject request(String model, AutoMemoryContextQuery query) {
        JSONArray messages = new JSONArray();
        messages.add(message("system", AutoMemoryRecallPlanningProtocol.SYSTEM_INSTRUCTION.strip()));
        messages.add(message("user", query.planningInputBinding().envelope(
                AutoMemoryRecallPlanningProtocol.render(query))));
        JSONObject request = new JSONObject(true);
        request.put("model", model);
        request.put("messages", messages);
        request.put("max_tokens", MAX_TOKENS);
        request.put("temperature", 0);
        request.put("response_format", Map.of("type", "json_object"));
        request.put("stream", false);
        return request;
    }

    private JSONObject message(String role, String content) {
        JSONObject message = new JSONObject(true);
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private boolean matches(JSONObject testCase, List<String> queries) {
        int expected = expectedCount(testCase);
        if (queries.size() != expected) {
            return false;
        }
        if (expected == 0) {
            return true;
        }
        List<List<String>> groups = testCase.getJSONArray("requiredIntentTerms").stream()
                .map(raw -> ((JSONArray) raw).toJavaList(String.class))
                .toList();
        return assign(groups, queries, 0, new boolean[queries.size()]);
    }

    private boolean assign(
            List<List<String>> groups,
            List<String> queries,
            int groupIndex,
            boolean[] used
    ) {
        if (groupIndex == groups.size()) {
            return true;
        }
        for (int index = 0; index < queries.size(); index++) {
            if (!used[index] && containsAll(queries.get(index), groups.get(groupIndex))) {
                used[index] = true;
                if (assign(groups, queries, groupIndex + 1, used)) {
                    return true;
                }
                used[index] = false;
            }
        }
        return false;
    }

    private boolean containsAll(String query, List<String> terms) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return terms.stream()
                .map(term -> term.toLowerCase(Locale.ROOT))
                .allMatch(normalized::contains);
    }

    private Metrics metrics(List<CaseRun> runs) {
        long multi = runs.stream().filter(run -> run.expectedCount() > 0).count();
        long single = runs.size() - multi;
        long matched = runs.stream().filter(CaseRun::matched).count();
        long multiMatched = runs.stream()
                .filter(run -> run.expectedCount() > 0 && run.matched()).count();
        long singleMatched = runs.stream()
                .filter(run -> run.expectedCount() == 0 && run.matched()).count();
        long protocolFailures = runs.stream().filter(run -> !run.protocolValid()).count();
        return new Metrics(
                ratio(matched, runs.size()),
                ratio(multiMatched, multi),
                ratio(singleMatched, single),
                ratio(protocolFailures, runs.size()));
    }

    private void writeReport(
            Path path,
            JSONObject cohort,
            Profile profile,
            String model,
            URI endpoint,
            Metrics metrics,
            boolean passed,
            List<CaseRun> runs
    ) throws Exception {
        JSONObject report = new JSONObject(true);
        report.put("schemaVersion", "AUTO_MEMORY_RECALL_PLANNING_REPORT_V1");
        report.put("generatedAt", Instant.now().toString());
        report.put("datasetVersion", cohort.getString("datasetVersion"));
        report.put("datasetSha256", profile.sha256());
        report.put("promptContractVersion", AutoMemoryRecallPlanningProtocol.CONTRACT_VERSION);
        report.put("model", model);
        report.put("endpoint", endpoint.getScheme() + "://" + endpoint.getHost());
        report.put("requestParameters", Map.of(
                "temperature", 0, "maxTokens", MAX_TOKENS, "responseFormat", "json_object"));
        report.put("businessDatabaseWrites", 0);
        report.put("gate", cohort.getJSONObject("qualityGate"));
        report.put("metrics", metrics);
        report.put("passed", passed);
        report.put("runs", runs);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, JSON.toJSONString(
                report,
                SerializerFeature.PrettyFormat,
                SerializerFeature.DisableCircularReferenceDetect), StandardCharsets.UTF_8);
    }

    private Profile profile() {
        return switch (environment(
                "AUTO_MEMORY_RECALL_PLANNING_EVALUATION_DATASET", "development-v1")) {
            case "development-v1" -> new Profile(
                    "auto-memory-recall-planning-development-v1/cohort.json",
                    DEVELOPMENT_SHA256,
                    false);
            case "holdout-v1" -> new Profile(
                    "auto-memory-recall-planning-v1/holdout.json",
                    HOLDOUT_SHA256,
                    true);
            default -> throw new IllegalArgumentException("unknown recall planning dataset");
        };
    }

    private Path cohortPath(Profile profile) {
        String relative = "ai-agent-draw-io-infrastructure/src/test/resources/evals/"
                + profile.relativePath();
        for (Path candidate : List.of(Path.of(relative), Path.of("..").resolve(relative))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("V1.8 recall planning dataset was not found");
    }

    private Path reportPath(String datasetVersion, String model) {
        String configured = System.getenv("AUTO_MEMORY_RECALL_PLANNING_EVALUATION_REPORT");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of("target", "auto-memory-recall-planning",
                datasetVersion + "-" + model + ".json").toAbsolutePath().normalize();
    }

    private int expectedCount(JSONObject testCase) {
        return testCase.getIntValue("expectedIntentCount");
    }

    private String requiredEnvironment(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalStateException("required V1.8 evaluation environment is missing");
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String withoutLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0d : (double) numerator / denominator;
    }

    private record Profile(String relativePath, String sha256, boolean enforceGate) {
    }

    private record Metrics(
            double caseAccuracy,
            double multiIntentCoverage,
            double singleIntentAbstention,
            double protocolFailureRate
    ) {
    }

    private record CaseRun(
            String caseId,
            int expectedCount,
            List<String> plannedQueries,
            boolean protocolValid,
            boolean matched,
            long latencyMillis,
            String errorCode,
            JSONObject usage
    ) {
        private CaseRun {
            plannedQueries = List.copyOf(plannedQueries);
        }
    }
}
