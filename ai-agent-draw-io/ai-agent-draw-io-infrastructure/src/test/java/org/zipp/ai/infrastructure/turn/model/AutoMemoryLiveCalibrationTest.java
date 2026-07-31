package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.zipp.ai.application.memory.AutoMemoryExtractionDraft;
import org.zipp.ai.application.memory.AutoMemoryExtractionInput;
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
        JSONObject cohort = cohort();
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
        Counters counters = new Counters();

        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (Object rawCase : cohort.getJSONArray("cases")) {
                JSONObject testCase = (JSONObject) rawCase;
                CalibrationRun run = execute(
                        client,
                        URI.create(baseUrl + "/" + completionsPath),
                        apiKey,
                        model,
                        testCase,
                        repetition);
                runs.add(run.report());
                counters.accept(testCase, run);
            }
        }

        JSONObject gate = cohort.getJSONObject("qualityGate");
        JSONObject metrics = counters.metrics();
        boolean passed = metrics.getDoubleValue("positiveScopeTypeAccuracy")
                >= gate.getDoubleValue("minimumPositiveScopeTypeAccuracy")
                && metrics.getDoubleValue("negativeExclusionRate")
                >= gate.getDoubleValue("minimumNegativeExclusionRate")
                && metrics.getDoubleValue("unsafeAcceptanceRate")
                <= gate.getDoubleValue("maximumUnsafeAcceptanceRate");

        JSONObject report = new JSONObject(true);
        report.put("schemaVersion", "AUTO_MEMORY_CALIBRATION_REPORT_V1");
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
        Path reportPath = reportPath(model, cohort.getString("datasetVersion"));
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
            int repetition
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
        AutoMemoryExtractionInput input = new AutoMemoryExtractionInput(
                turn,
                "calibration-diagram",
                testCase.getBooleanValue("chartbookAvailable")
                        ? "calibration-chartbook" : null,
                userTurn,
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
                    AutoMemoryExtractionProtocol.parse(output, input.hasChartbook());
            boolean matched = matches(testCase.getJSONArray("expected"), drafts);
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

    private boolean matches(
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

    private void add(Map<String, Integer> counts, String value) {
        counts.merge(value, 1, Integer::sum);
    }

    private JSONObject cohort() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/evals/auto-memory-v1/cohort.json")) {
            assertNotNull(input);
            return JSON.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private Path reportPath(String model, String datasetVersion) {
        String configured = System.getenv("AUTO_MEMORY_CALIBRATION_REPORT");
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

    private static final class Counters {
        private int positiveRuns;
        private int positiveMatches;
        private int negativeRuns;
        private int negativeExclusions;
        private int unsafeRuns;
        private int unsafeAcceptances;
        private int protocolFailures;

        private void accept(JSONObject testCase, CalibrationRun run) {
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

        private JSONObject metrics() {
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

        private double ratio(int numerator, int denominator) {
            return denominator == 0 ? 0.0d : (double) numerator / denominator;
        }
    }
}
