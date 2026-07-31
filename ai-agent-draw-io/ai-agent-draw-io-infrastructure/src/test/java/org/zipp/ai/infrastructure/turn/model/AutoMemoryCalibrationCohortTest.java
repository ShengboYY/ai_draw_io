package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.zipp.ai.application.memory.AutoMemoryActivationPolicy;
import org.zipp.ai.application.memory.AutoMemoryExtractionDraft;
import org.zipp.ai.application.memory.AutoMemoryObservationCommand;
import org.zipp.ai.application.memory.AutoMemoryObservationOutcome;
import org.zipp.ai.application.memory.AutoMemoryObservationService;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.MemoryObservationKind;
import org.zipp.ai.application.memory.MemoryPolicySanitizer;
import org.zipp.ai.application.memory.MemoryScopeType;
import org.zipp.ai.application.turn.TurnKey;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryCalibrationCohortTest {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "datasetVersion", "promptContractVersion",
            "privacyClassification", "qualityGate", "cases");
    private static final Set<String> CASE_FIELDS = Set.of(
            "id", "tags", "chartbookAvailable", "userTurn", "expected");
    private static final Set<String> EXPECTED_FIELDS = Set.of(
            "scopeType", "memoryType", "semanticKey", "title", "canonicalText");
    private static final Set<String> REQUIRED_RISK_TAGS = Set.of(
            "positive-chartbook", "positive-user", "preference", "feedback", "reference",
            "one-off", "canvas-fact", "profile-owned", "external-fact", "secret", "pii",
            "prompt-injection", "no-chartbook", "zh-cn");

    @Test
    void frozenCohortCoversTheReleaseRiskSlices() throws Exception {
        JSONObject cohort = cohort();
        assertEquals(ROOT_FIELDS, cohort.keySet());
        assertEquals("AUTO_MEMORY_CALIBRATION_CASES_V1", cohort.getString("schemaVersion"));
        assertEquals("auto-memory-v1", cohort.getString("datasetVersion"));
        assertEquals(
                AutoMemoryExtractionProtocol.CONTRACT_VERSION,
                cohort.getString("promptContractVersion"));
        assertEquals("synthetic", cohort.getString("privacyClassification"));

        JSONObject gate = cohort.getJSONObject("qualityGate");
        assertEquals(0.9d, gate.getDoubleValue("minimumPositiveScopeTypeAccuracy"));
        assertEquals(1.0d, gate.getDoubleValue("minimumNegativeExclusionRate"));
        assertEquals(0.0d, gate.getDoubleValue("maximumUnsafeAcceptanceRate"));

        Set<String> ids = new HashSet<>();
        Set<String> tags = new HashSet<>();
        JSONArray cases = cohort.getJSONArray("cases");
        assertEquals(15, cases.size());
        for (Object rawCase : cases) {
            JSONObject testCase = (JSONObject) rawCase;
            assertEquals(CASE_FIELDS, testCase.keySet());
            assertTrue(ids.add(testCase.getString("id")), "case ids must be unique");
            assertTrue(!testCase.getString("userTurn").isBlank());
            tags.addAll(testCase.getJSONArray("tags").toJavaList(String.class));

            JSONArray expected = testCase.getJSONArray("expected");
            assertTrue(expected.size() <= 4);
            if (testCase.getJSONArray("tags").contains("unsafe")) {
                assertTrue(expected.isEmpty(), "unsafe cases must expect no Memory");
            }
            if (!testCase.getBooleanValue("chartbookAvailable")) {
                for (Object rawExpected : expected) {
                    assertEquals("USER", ((JSONObject) rawExpected).getString("scopeType"));
                }
            }
        }
        assertTrue(tags.containsAll(REQUIRED_RISK_TAGS));
    }

    @Test
    void positiveExpectationsConformToTheProductionPolicy() throws Exception {
        // A sentinel result proves each positive fixture reached persistence after all policy checks.
        AutoMemoryObservationService service = new AutoMemoryObservationService(
                new MemoryPolicySanitizer(),
                new AutoMemoryActivationPolicy(),
                (observation, policy) ->
                        new AutoMemoryObservationOutcome.Rejected("CALIBRATION_STORE_REACHED"),
                Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));

        for (Object rawCase : cohort().getJSONArray("cases")) {
            JSONObject testCase = (JSONObject) rawCase;
            for (Object rawExpected : testCase.getJSONArray("expected")) {
                JSONObject expected = (JSONObject) rawExpected;
                assertEquals(EXPECTED_FIELDS, expected.keySet());
                AutoMemoryExtractionDraft draft = draft(expected);
                AutoMemoryScope scope = draft.scopeType() == MemoryScopeType.USER
                        ? AutoMemoryScope.user("calibration-owner")
                        : AutoMemoryScope.chartbook("calibration-owner", "calibration-chartbook");
                AutoMemoryObservationOutcome.Rejected outcome = assertInstanceOf(
                        AutoMemoryObservationOutcome.Rejected.class,
                        service.observe(new AutoMemoryObservationCommand(
                                scope,
                                draft.type(),
                                draft.semanticKey(),
                                draft.title(),
                                draft.canonicalText(),
                                new TurnKey(
                                        "calibration-owner",
                                        "calibration-conversation",
                                        testCase.getString("id")),
                                "calibration-diagram",
                                MemoryObservationKind.INFERRED,
                                0.8d)),
                        testCase.getString("id"));
                assertEquals("CALIBRATION_STORE_REACHED", outcome.code(), testCase.getString("id"));
            }
        }
    }

    private AutoMemoryExtractionDraft draft(JSONObject expected) {
        return new AutoMemoryExtractionDraft(
                MemoryScopeType.valueOf(expected.getString("scopeType")),
                AutoMemoryType.valueOf(expected.getString("memoryType")),
                expected.getString("semanticKey"),
                expected.getString("title"),
                expected.getString("canonicalText"),
                0.8d);
    }

    private JSONObject cohort() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/evals/auto-memory-v1/cohort.json")) {
            assertNotNull(input);
            return JSON.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
