package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.zipp.ai.application.memory.AutoMemoryExtractionCandidate;
import org.zipp.ai.application.memory.AutoMemoryExtractionDraft;
import org.zipp.ai.application.memory.AutoMemoryExtractionInput;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.MemoryScopeType;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.TurnKey;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicitly enabled live quality gate. It stores only synthetic final JSON outputs and usage;
 * credentials and provider reasoning are never written to the report.
 */
@EnabledIfEnvironmentVariable(
        named = "AUTO_MEMORY_LIVE_CALIBRATION_ENABLED",
        matches = "true")
class AutoMemoryLiveCalibrationTest {
    private static final int DEFAULT_REPETITIONS = 3;

    @Test
    void deepSeekCohortMeetsTheReleaseGate() throws Exception {
        runReleaseGate(
                "/evals/auto-memory-v1/cohort.json",
                "AUTO_MEMORY_CALIBRATION_REPORT_V1",
                "AUTO_MEMORY_CALIBRATION_REPORT",
                new ExtractionEvaluator());
    }

    @Test
    void deepSeekConsolidationCohortMeetsTheReleaseGate() throws Exception {
        runReleaseGate(
                "/evals/auto-memory-consolidation-v1/cohort.json",
                "AUTO_MEMORY_CONSOLIDATION_REPORT_V1",
                "AUTO_MEMORY_CONSOLIDATION_REPORT",
                new ConsolidationEvaluator());
    }

    private void runReleaseGate(
            String cohortResource,
            String reportSchema,
            String reportEnvironment,
            CohortEvaluator evaluator
    ) throws Exception {
        JSONObject cohort = cohort(cohortResource);
        String model = environment("AUTO_MEMORY_MODEL", "deepseek-v4-pro");
        String baseUrl = withoutTrailingSlash(
                environment("AUTO_MEMORY_BASE_URL", "https://api.deepseek.com"));
        String completionsPath = withoutLeadingSlash(
                environment("AUTO_MEMORY_COMPLETIONS_PATH", "v1/chat/completions"));
        String apiKey = requiredEnvironment(
                "AUTO_MEMORY_API_KEY",
                "LLM_API_KEY_deepseek");
        int repetitions = positiveInteger(
                environment(
                        "AUTO_MEMORY_CALIBRATION_REPETITIONS",
                        Integer.toString(DEFAULT_REPETITIONS)));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        JSONArray runs = new JSONArray();

        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (Object rawCase : cohort.getJSONArray("cases")) {
                JSONObject testCase = (JSONObject) rawCase;
                CalibrationRun run = execute(
                        client,
                        URI.create(baseUrl + "/" + completionsPath),
                        apiKey,
                        model,
                        testCase,
                        repetition,
                        evaluator);
                runs.add(run.report());
                evaluator.accept(testCase, run);
            }
        }

        JSONObject gate = cohort.getJSONObject("qualityGate");
        JSONObject metrics = evaluator.metrics();
        boolean passed = evaluator.passed(gate);

        JSONObject report = new JSONObject(true);
        report.put("schemaVersion", reportSchema);
        report.put("generatedAt", Instant.now().toString());
        report.put("datasetVersion", cohort.getString("datasetVersion"));
        report.put("promptContractVersion", AutoMemoryExtractionProtocol.CONTRACT_VERSION);
        report.put("model", model);
        report.put("endpoint", baseUrl);
        report.put("requestParameters", Map.of(
                "temperature", 0,
                "maxTokens", 1_024,
                "responseFormat", "json_object"));
        report.put("repetitions", repetitions);
        report.put("gate", gate);
        report.put("metrics", metrics);
        report.put("passed", passed);
        report.put("runs", runs);
        Path reportPath = reportPath(
                model,
                cohort.getString("datasetVersion"),
                reportEnvironment);
        Files.createDirectories(reportPath.toAbsolutePath().getParent());
        Files.writeString(
                reportPath,
                JSON.toJSONString(
                        report,
                        SerializerFeature.PrettyFormat,
                        SerializerFeature.DisableCircularReferenceDetect),
                StandardCharsets.UTF_8);

        assertTrue(passed, () -> "Auto Memory live calibration failed: "
                + metrics + "; report=" + reportPath.toAbsolutePath());
    }

    private CalibrationRun execute(
            HttpClient client,
            URI endpoint,
            String apiKey,
            String model,
            JSONObject testCase,
            int repetition,
            CohortEvaluator evaluator
    ) throws Exception {
        String caseId = testCase.getString("id");
        TurnKey turn = new TurnKey(
                "calibration-owner",
                "calibration-conversation",
                caseId + "-" + repetition);
        String userTurn = testCase.getString("userTurn");
        String contextDigest = ModelInputBinding.digestOf(
                "auto-memory-calibration",
                Boolean.toString(testCase.getBooleanValue("chartbookAvailable")));
        List<AutoMemoryExtractionCandidate> candidates = candidates(testCase);
        AutoMemoryExtractionInput input = new AutoMemoryExtractionInput(
                turn,
                "calibration-diagram",
                testCase.getBooleanValue("chartbookAvailable")
                        ? "calibration-chartbook" : null,
                userTurn,
                candidates,
                ModelInputBinding.bound(
                        turn,
                        contextDigest,
                        ModelInputBinding.digestOf(userTurn, contextDigest)));

        JSONObject request = request(model, input);
        long started = System.nanoTime();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(45))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                JSON.toJSONString(request),
                                StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long latencyMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        JSONObject runReport = new JSONObject(true);
        runReport.put("caseId", caseId);
        runReport.put("repetition", repetition);
        runReport.put("tags", testCase.getJSONArray("tags"));
        runReport.put("latencyMillis", latencyMillis);
        runReport.put("httpStatus", response.statusCode());
        if (response.statusCode() != 200) {
            runReport.put("protocolValid", false);
            runReport.put("matched", false);
            runReport.put("errorCode", "HTTP_" + response.statusCode());
            return new CalibrationRun(runReport, List.of(), false, false);
        }

        JSONObject body = JSON.parseObject(response.body());
        JSONObject choice = body.getJSONArray("choices").getJSONObject(0);
        String output = choice.getJSONObject("message").getString("content");
        runReport.put("providerModel", body.getString("model"));
        runReport.put("systemFingerprint", body.getString("system_fingerprint"));
        runReport.put("finishReason", choice.getString("finish_reason"));
        runReport.put("output", output);
        runReport.put("usage", body.getJSONObject("usage"));

        try {
            List<AutoMemoryExtractionDraft> drafts =
                    AutoMemoryExtractionProtocol.parse(output, input);
            boolean matched = evaluator.matches(testCase, drafts);
            runReport.put("protocolValid", true);
            runReport.put("matched", matched);
            return new CalibrationRun(runReport, drafts, true, matched);
        } catch (RuntimeException failure) {
            runReport.put("protocolValid", false);
            runReport.put("matched", false);
            runReport.put("errorCode", failure.getClass().getSimpleName());
            return new CalibrationRun(runReport, List.of(), false, false);
        }
    }

    private JSONObject request(String model, AutoMemoryExtractionInput input) {
        JSONArray messages = new JSONArray();
        messages.add(message("system", AutoMemoryExtractionProtocol.SYSTEM_INSTRUCTION.strip()));
        messages.add(message(
                "user",
                input.modelInputBinding().envelope(
                        AutoMemoryExtractionProtocol.render(input))));
        JSONObject request = new JSONObject(true);
        request.put("model", model);
        request.put("messages", messages);
        request.put("max_tokens", 1_024);
        request.put("temperature", 0);
        // Match the fixed production agent's provider-level JSON mode.
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

    private static boolean matchesExtraction(
            JSONArray expected,
            List<AutoMemoryExtractionDraft> actual
    ) {
        if (expected.size() != actual.size()) {
            return false;
        }
        Map<String, Integer> expectedSignatures = new HashMap<>();
        for (Object rawExpected : expected) {
            JSONObject value = (JSONObject) rawExpected;
            add(expectedSignatures,
                    value.getString("scopeType") + ":" + value.getString("memoryType"));
        }
        Map<String, Integer> actualSignatures = new HashMap<>();
        for (AutoMemoryExtractionDraft draft : actual) {
            add(actualSignatures, draft.scopeType().name() + ":" + draft.type().name());
        }
        return expectedSignatures.equals(actualSignatures);
    }

    private static void add(Map<String, Integer> counts, String value) {
        counts.merge(value, 1, Integer::sum);
    }

    static boolean matchesConsolidation(
            JSONObject testCase,
            List<AutoMemoryExtractionDraft> actual
    ) {
        JSONObject expected = testCase.getJSONObject("expected");
        String outcome = expected.getString("outcome");
        if ("EMPTY".equals(outcome)) {
            return actual.isEmpty();
        }
        if (actual.size() != 1) {
            return false;
        }
        AutoMemoryExtractionDraft draft = actual.get(0);
        if ("CREATE".equals(outcome)) {
            boolean expectedShape = draft.scopeType().name().equals(expected.getString("scopeType"))
                    && draft.type().name().equals(expected.getString("memoryType"));
            return expectedShape && candidates(testCase).stream()
                    .noneMatch(candidate -> sameIdentity(candidate, draft));
        }
        if (!"REUSE".equals(outcome)) {
            return false;
        }
        return candidates(testCase).stream()
                .filter(candidate -> candidate.scopeType().name()
                        .equals(expected.getString("scopeType")))
                .filter(candidate -> candidate.semanticKey()
                        .equals(expected.getString("semanticKey")))
                .findFirst()
                .map(candidate -> sameContent(candidate, draft))
                .orElse(false);
    }

    private static boolean sameIdentity(
            AutoMemoryExtractionCandidate candidate,
            AutoMemoryExtractionDraft draft
    ) {
        return candidate.scopeType() == draft.scopeType()
                && candidate.semanticKey().equals(draft.semanticKey());
    }

    private static boolean sameContent(
            AutoMemoryExtractionCandidate candidate,
            AutoMemoryExtractionDraft draft
    ) {
        return sameIdentity(candidate, draft)
                && candidate.type() == draft.type()
                && candidate.title().equals(draft.title())
                && candidate.canonicalText().equals(draft.canonicalText());
    }

    private static List<AutoMemoryExtractionCandidate> candidates(JSONObject testCase) {
        JSONArray values = testCase.getJSONArray("existingCandidates");
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<AutoMemoryExtractionCandidate> candidates = new ArrayList<>(values.size());
        for (Object rawValue : values) {
            JSONObject value = (JSONObject) rawValue;
            candidates.add(new AutoMemoryExtractionCandidate(
                    MemoryScopeType.valueOf(value.getString("scopeType")),
                    AutoMemoryType.valueOf(value.getString("memoryType")),
                    value.getString("semanticKey"),
                    value.getString("title"),
                    value.getString("canonicalText"),
                    AutoMemoryStatus.valueOf(value.getString("status"))));
        }
        return List.copyOf(candidates);
    }

    private JSONObject cohort(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            return JSON.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private Path reportPath(String model, String datasetVersion, String reportEnvironment) {
        String configured = System.getenv(reportEnvironment);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(
                "target",
                "auto-memory-calibration",
                model + "-" + datasetVersion + ".json");
    }

    private static String requiredEnvironment(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalStateException("Auto Memory calibration API key is not configured");
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int positiveInteger(String value) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("calibration repetitions must be positive");
        }
        return parsed;
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String withoutLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private record CalibrationRun(
            JSONObject report,
            List<AutoMemoryExtractionDraft> drafts,
            boolean protocolValid,
            boolean matched
    ) {
    }

    private interface CohortEvaluator {
        boolean matches(JSONObject testCase, List<AutoMemoryExtractionDraft> actual);

        void accept(JSONObject testCase, CalibrationRun run);

        JSONObject metrics();

        boolean passed(JSONObject gate);
    }

    private static final class ExtractionEvaluator implements CohortEvaluator {
        private int positiveRuns;
        private int positiveMatches;
        private int negativeRuns;
        private int negativeExclusions;
        private int unsafeRuns;
        private int unsafeAcceptances;
        private int protocolFailures;

        @Override
        public boolean matches(JSONObject testCase, List<AutoMemoryExtractionDraft> actual) {
            return matchesExtraction(testCase.getJSONArray("expected"), actual);
        }

        @Override
        public void accept(JSONObject testCase, CalibrationRun run) {
            boolean positive = !testCase.getJSONArray("expected").isEmpty();
            boolean unsafe = testCase.getJSONArray("tags").contains("unsafe");
            if (!run.protocolValid()) {
                protocolFailures++;
            }
            if (positive) {
                positiveRuns++;
                if (run.matched()) {
                    positiveMatches++;
                }
            } else {
                negativeRuns++;
                if (run.protocolValid() && run.drafts().isEmpty()) {
                    negativeExclusions++;
                }
            }
            if (unsafe) {
                unsafeRuns++;
                if (!run.drafts().isEmpty()) {
                    unsafeAcceptances++;
                }
            }
        }

        @Override
        public JSONObject metrics() {
            JSONObject metrics = new JSONObject(true);
            metrics.put("positiveRuns", positiveRuns);
            metrics.put("positiveMatches", positiveMatches);
            metrics.put(
                    "positiveScopeTypeAccuracy",
                    ratio(positiveMatches, positiveRuns));
            metrics.put("negativeRuns", negativeRuns);
            metrics.put("negativeExclusions", negativeExclusions);
            metrics.put(
                    "negativeExclusionRate",
                    ratio(negativeExclusions, negativeRuns));
            metrics.put("unsafeRuns", unsafeRuns);
            metrics.put("unsafeAcceptances", unsafeAcceptances);
            metrics.put(
                    "unsafeAcceptanceRate",
                    ratio(unsafeAcceptances, unsafeRuns));
            metrics.put("protocolFailures", protocolFailures);
            return metrics;
        }

        @Override
        public boolean passed(JSONObject gate) {
            JSONObject values = metrics();
            return values.getDoubleValue("positiveScopeTypeAccuracy")
                    >= gate.getDoubleValue("minimumPositiveScopeTypeAccuracy")
                    && values.getDoubleValue("negativeExclusionRate")
                    >= gate.getDoubleValue("minimumNegativeExclusionRate")
                    && values.getDoubleValue("unsafeAcceptanceRate")
                    <= gate.getDoubleValue("maximumUnsafeAcceptanceRate");
        }
    }

    private static final class ConsolidationEvaluator implements CohortEvaluator {
        private int reuseRuns;
        private int reuseMatches;
        private int createRuns;
        private int createMatches;
        private int falseMerges;
        private int emptyRuns;
        private int emptyMatches;
        private int crossScopeRuns;
        private int crossScopeMerges;
        private int disabledRuns;
        private int disabledBypasses;
        private int unsafeRuns;
        private int unsafeCandidateAcceptances;
        private int protocolFailures;

        @Override
        public boolean matches(JSONObject testCase, List<AutoMemoryExtractionDraft> actual) {
            return matchesConsolidation(testCase, actual);
        }

        @Override
        public void accept(JSONObject testCase, CalibrationRun run) {
            String outcome = testCase.getJSONObject("expected").getString("outcome");
            boolean mergedCandidate = candidates(testCase).stream()
                    .anyMatch(candidate -> run.drafts().stream()
                            .anyMatch(draft -> sameIdentity(candidate, draft)));
            if (!run.protocolValid()) {
                protocolFailures++;
            }
            if ("REUSE".equals(outcome)) {
                reuseRuns++;
                if (run.matched()) {
                    reuseMatches++;
                }
            } else if ("CREATE".equals(outcome)) {
                createRuns++;
                if (run.matched()) {
                    createMatches++;
                }
                if (mergedCandidate) {
                    falseMerges++;
                }
            } else if ("EMPTY".equals(outcome)) {
                emptyRuns++;
                if (run.matched()) {
                    emptyMatches++;
                }
            }
            JSONArray tags = testCase.getJSONArray("tags");
            if (tags.contains("cross-scope")) {
                crossScopeRuns++;
                if (mergedCandidate) {
                    crossScopeMerges++;
                }
            }
            if (tags.contains("disabled")) {
                disabledRuns++;
                if (!run.matched()) {
                    disabledBypasses++;
                }
            }
            if (tags.contains("unsafe-candidate")) {
                unsafeRuns++;
                if (mergedCandidate) {
                    unsafeCandidateAcceptances++;
                }
            }
        }

        @Override
        public JSONObject metrics() {
            JSONObject metrics = new JSONObject(true);
            metrics.put("reuseRuns", reuseRuns);
            metrics.put("reuseMatches", reuseMatches);
            metrics.put("reuseAccuracy", ratio(reuseMatches, reuseRuns));
            metrics.put("createRuns", createRuns);
            metrics.put("createMatches", createMatches);
            metrics.put("createAccuracy", ratio(createMatches, createRuns));
            metrics.put("falseMerges", falseMerges);
            metrics.put("falseMergeRate", ratio(falseMerges, createRuns));
            metrics.put("emptyRuns", emptyRuns);
            metrics.put("emptyMatches", emptyMatches);
            metrics.put("emptyAccuracy", ratio(emptyMatches, emptyRuns));
            metrics.put("crossScopeRuns", crossScopeRuns);
            metrics.put("crossScopeMerges", crossScopeMerges);
            metrics.put("crossScopeMergeRate", ratio(crossScopeMerges, crossScopeRuns));
            metrics.put("disabledRuns", disabledRuns);
            metrics.put("disabledBypasses", disabledBypasses);
            metrics.put("disabledBypassRate", ratio(disabledBypasses, disabledRuns));
            metrics.put("unsafeRuns", unsafeRuns);
            metrics.put("unsafeCandidateAcceptances", unsafeCandidateAcceptances);
            metrics.put(
                    "unsafeCandidateAcceptanceRate",
                    ratio(unsafeCandidateAcceptances, unsafeRuns));
            metrics.put("protocolFailures", protocolFailures);
            return metrics;
        }

        @Override
        public boolean passed(JSONObject gate) {
            JSONObject values = metrics();
            return values.getDoubleValue("reuseAccuracy")
                    >= gate.getDoubleValue("minimumReuseAccuracy")
                    && values.getDoubleValue("createAccuracy")
                    >= gate.getDoubleValue("minimumCreateAccuracy")
                    && values.getDoubleValue("emptyAccuracy")
                    >= gate.getDoubleValue("minimumEmptyAccuracy")
                    && values.getDoubleValue("falseMergeRate")
                    <= gate.getDoubleValue("maximumFalseMergeRate")
                    && values.getDoubleValue("crossScopeMergeRate")
                    <= gate.getDoubleValue("maximumCrossScopeMergeRate")
                    && values.getDoubleValue("disabledBypassRate")
                    <= gate.getDoubleValue("maximumDisabledBypassRate")
                    && values.getDoubleValue("unsafeCandidateAcceptanceRate")
                    <= gate.getDoubleValue("maximumUnsafeCandidateAcceptanceRate")
                    && values.getIntValue("protocolFailures")
                    <= gate.getIntValue("maximumProtocolFailures");
        }
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0d : (double) numerator / denominator;
    }
}
