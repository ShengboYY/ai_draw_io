package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.zipp.ai.application.memory.AutoMemoryExtractionCandidate;
import org.zipp.ai.application.memory.AutoMemoryExtractionDraft;
import org.zipp.ai.application.memory.AutoMemoryExtractionInput;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.MemoryScopeType;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.TurnKey;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryConsolidationCohortTest {
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "datasetVersion", "promptContractVersion",
            "privacyClassification", "qualityGate", "cases");
    private static final Set<String> CASE_FIELDS = Set.of(
            "id", "tags", "chartbookAvailable", "userTurn", "existingCandidates", "expected");
    private static final Set<String> CANDIDATE_FIELDS = Set.of(
            "scopeType", "memoryType", "semanticKey", "title", "canonicalText", "status");
    private static final Set<String> REQUIRED_TAGS = Set.of(
            "reuse", "create", "empty", "active", "observed", "disabled", "best-of-many",
            "related", "cross-scope", "unsafe-candidate", "uncertain", "zh-cn");
    private static final Set<String> CONFLICT_REQUIRED_TAGS = Set.of(
            "challenge", "reuse", "create", "empty", "active", "observed", "disabled",
            "not-challenge", "cross-scope", "unsafe-candidate", "uncertain", "zh-cn");

    @Test
    void frozenCohortCoversConsolidationReleaseRisks() throws Exception {
        JSONObject cohort = cohort();
        assertEquals(ROOT_FIELDS, cohort.keySet());
        assertEquals(
                "AUTO_MEMORY_CONSOLIDATION_CASES_V1",
                cohort.getString("schemaVersion"));
        assertEquals("auto-memory-consolidation-v1", cohort.getString("datasetVersion"));
        assertEquals(
                AutoMemoryExtractionProtocol.CONTRACT_VERSION,
                cohort.getString("promptContractVersion"));
        assertEquals("synthetic", cohort.getString("privacyClassification"));

        JSONObject gate = cohort.getJSONObject("qualityGate");
        assertEquals(0.95d, gate.getDoubleValue("minimumReuseAccuracy"));
        assertEquals(1.0d, gate.getDoubleValue("minimumCreateAccuracy"));
        assertEquals(1.0d, gate.getDoubleValue("minimumEmptyAccuracy"));
        assertEquals(0.0d, gate.getDoubleValue("maximumFalseMergeRate"));
        assertEquals(0.0d, gate.getDoubleValue("maximumCrossScopeMergeRate"));
        assertEquals(0.0d, gate.getDoubleValue("maximumDisabledBypassRate"));
        assertEquals(0.0d, gate.getDoubleValue("maximumUnsafeCandidateAcceptanceRate"));
        assertEquals(0, gate.getIntValue("maximumProtocolFailures"));

        Set<String> ids = new HashSet<>();
        Set<String> tags = new HashSet<>();
        JSONArray cases = cohort.getJSONArray("cases");
        assertEquals(8, cases.size());
        for (Object rawCase : cases) {
            JSONObject testCase = (JSONObject) rawCase;
            assertEquals(CASE_FIELDS, testCase.keySet());
            assertTrue(ids.add(testCase.getString("id")), "case ids must be unique");
            assertTrue(!testCase.getString("userTurn").isBlank());
            tags.addAll(testCase.getJSONArray("tags").toJavaList(String.class));

            List<AutoMemoryExtractionCandidate> candidates = candidates(testCase);
            assertTrue(!candidates.isEmpty());
            assertTrue(candidates.size() <= AutoMemoryExtractionInput.MAX_EXISTING_CANDIDATES);
            assertExpected(testCase.getJSONObject("expected"), candidates);
            assertInputAccepted(testCase, candidates);
        }
        assertTrue(tags.containsAll(REQUIRED_TAGS));
    }

    @Test
    void fixtureExpectationsExerciseTheOfflineMatcher() throws Exception {
        assertFixtureExpectations(cohort());
    }

    @Test
    void frozenConflictCohortCoversV13ReleaseRisks() throws Exception {
        JSONObject cohort = cohort("/evals/auto-memory-conflict-v1/cohort.json");
        assertEquals(ROOT_FIELDS, cohort.keySet());
        assertEquals("AUTO_MEMORY_CONFLICT_CASES_V1", cohort.getString("schemaVersion"));
        assertEquals("auto-memory-conflict-v1", cohort.getString("datasetVersion"));
        assertEquals(
                AutoMemoryExtractionProtocol.CONTRACT_VERSION,
                cohort.getString("promptContractVersion"));
        assertEquals("synthetic", cohort.getString("privacyClassification"));

        JSONObject gate = cohort.getJSONObject("qualityGate");
        assertEquals(0.95d, gate.getDoubleValue("minimumChallengeAccuracy"));
        assertEquals(0.0d, gate.getDoubleValue("maximumFalseMergeRate"));
        assertEquals(0.0d, gate.getDoubleValue("maximumCrossScopeMergeRate"));
        assertEquals(0.0d, gate.getDoubleValue("maximumDisabledBypassRate"));
        assertEquals(0, gate.getIntValue("maximumProtocolFailures"));

        Set<String> ids = new HashSet<>();
        Set<String> tags = new HashSet<>();
        JSONArray cases = cohort.getJSONArray("cases");
        assertEquals(8, cases.size());
        for (Object rawCase : cases) {
            JSONObject testCase = (JSONObject) rawCase;
            assertEquals(CASE_FIELDS, testCase.keySet());
            assertTrue(ids.add(testCase.getString("id")), "case ids must be unique");
            tags.addAll(testCase.getJSONArray("tags").toJavaList(String.class));
            List<AutoMemoryExtractionCandidate> candidates = candidates(testCase);
            assertTrue(!candidates.isEmpty());
            assertExpected(testCase.getJSONObject("expected"), candidates);
            assertInputAccepted(testCase, candidates);
        }
        assertTrue(tags.containsAll(CONFLICT_REQUIRED_TAGS));
    }

    @Test
    void conflictFixtureExpectationsExerciseTheOfflineMatcher() throws Exception {
        assertFixtureExpectations(cohort("/evals/auto-memory-conflict-v1/cohort.json"));
    }

    private void assertFixtureExpectations(JSONObject cohort) {
        for (Object rawCase : cohort.getJSONArray("cases")) {
            JSONObject testCase = (JSONObject) rawCase;
            List<AutoMemoryExtractionCandidate> candidates = candidates(testCase);
            JSONObject expected = testCase.getJSONObject("expected");
            List<AutoMemoryExtractionDraft> matching = matchingDrafts(
                    testCase,
                    expected,
                    candidates);
            assertTrue(
                    AutoMemoryLiveCalibrationTest.matchesConsolidation(testCase, matching),
                    testCase.getString("id"));

            List<AutoMemoryExtractionDraft> nonMatching = "REUSE".equals(expected.getString("outcome"))
                    ? List.of()
                    : List.of(draft(candidates.get(0)));
            assertFalse(
                    AutoMemoryLiveCalibrationTest.matchesConsolidation(testCase, nonMatching),
                    testCase.getString("id"));
        }
    }

    private void assertExpected(
            JSONObject expected,
            List<AutoMemoryExtractionCandidate> candidates
    ) {
        String outcome = expected.getString("outcome");
        if ("REUSE".equals(outcome)) {
            assertEquals(Set.of("outcome", "scopeType", "semanticKey"), expected.keySet());
            assertTrue(candidates.stream().anyMatch(candidate ->
                    candidate.scopeType().name().equals(expected.getString("scopeType"))
                            && candidate.semanticKey().equals(expected.getString("semanticKey"))));
        } else if ("CHALLENGE".equals(outcome)) {
            assertEquals(Set.of("outcome", "scopeType", "semanticKey"), expected.keySet());
            assertTrue(candidates.stream().anyMatch(candidate ->
                    candidate.scopeType().name().equals(expected.getString("scopeType"))
                            && candidate.semanticKey().equals(expected.getString("semanticKey"))));
        } else if ("CREATE".equals(outcome)) {
            assertEquals(Set.of("outcome", "scopeType", "memoryType"), expected.keySet());
            MemoryScopeType.valueOf(expected.getString("scopeType"));
            AutoMemoryType.valueOf(expected.getString("memoryType"));
        } else {
            assertEquals("EMPTY", outcome);
            assertEquals(Set.of("outcome"), expected.keySet());
        }
    }

    private void assertInputAccepted(
            JSONObject testCase,
            List<AutoMemoryExtractionCandidate> candidates
    ) {
        String caseId = testCase.getString("id");
        TurnKey turn = new TurnKey("calibration-owner", "calibration-conversation", caseId);
        String digest = ModelInputBinding.digestOf("consolidation-cohort", caseId);
        new AutoMemoryExtractionInput(
                turn,
                "calibration-diagram",
                testCase.getBooleanValue("chartbookAvailable")
                        ? "calibration-chartbook" : null,
                testCase.getString("userTurn"),
                candidates,
                ModelInputBinding.bound(turn, digest, digest));
    }

    private List<AutoMemoryExtractionDraft> matchingDrafts(
            JSONObject testCase,
            JSONObject expected,
            List<AutoMemoryExtractionCandidate> candidates
    ) {
        String outcome = expected.getString("outcome");
        if ("EMPTY".equals(outcome)) {
            return List.of();
        }
        if ("REUSE".equals(outcome)) {
            return candidates.stream()
                    .filter(candidate -> candidate.scopeType().name()
                            .equals(expected.getString("scopeType")))
                    .filter(candidate -> candidate.semanticKey()
                            .equals(expected.getString("semanticKey")))
                    .findFirst()
                    .map(candidate -> List.of(draft(candidate)))
                    .orElseThrow();
        }
        if ("CHALLENGE".equals(outcome)) {
            return candidates.stream()
                    .filter(candidate -> candidate.scopeType().name()
                            .equals(expected.getString("scopeType")))
                    .filter(candidate -> candidate.semanticKey()
                            .equals(expected.getString("semanticKey")))
                    .findFirst()
                    .map(candidate -> List.of(new AutoMemoryExtractionDraft(
                            candidate.scopeType(),
                            candidate.type(),
                            candidate.semanticKey(),
                            candidate.title(),
                            "A changed durable value",
                            0.8d)))
                    .orElseThrow();
        }
        String semanticKey = testCase.getJSONArray("tags").contains("cross-scope")
                ? candidates.get(0).semanticKey()
                : "new-" + testCase.getString("id");
        // A cross-scope item may keep the semantic key because scope is part of persistence identity.
        return List.of(new AutoMemoryExtractionDraft(
                MemoryScopeType.valueOf(expected.getString("scopeType")),
                AutoMemoryType.valueOf(expected.getString("memoryType")),
                semanticKey,
                "New Memory",
                "A distinct durable preference",
                0.8d));
    }

    private AutoMemoryExtractionDraft draft(AutoMemoryExtractionCandidate candidate) {
        return new AutoMemoryExtractionDraft(
                candidate.scopeType(),
                candidate.type(),
                candidate.semanticKey(),
                candidate.title(),
                candidate.canonicalText(),
                0.8d);
    }

    private List<AutoMemoryExtractionCandidate> candidates(JSONObject testCase) {
        List<AutoMemoryExtractionCandidate> candidates = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (Object rawCandidate : testCase.getJSONArray("existingCandidates")) {
            JSONObject value = (JSONObject) rawCandidate;
            assertEquals(CANDIDATE_FIELDS, value.keySet());
            AutoMemoryExtractionCandidate candidate = new AutoMemoryExtractionCandidate(
                    MemoryScopeType.valueOf(value.getString("scopeType")),
                    AutoMemoryType.valueOf(value.getString("memoryType")),
                    value.getString("semanticKey"),
                    value.getString("title"),
                    value.getString("canonicalText"),
                    AutoMemoryStatus.valueOf(value.getString("status")));
            // Even defensive hostile-data cases must retain a valid server-owned candidate identity.
            assertTrue(identities.add(candidate.scopeType() + ":" + candidate.semanticKey()));
            assertTrue(candidate.status() != AutoMemoryStatus.DELETED);
            candidates.add(candidate);
        }
        return List.copyOf(candidates);
    }

    private JSONObject cohort() throws Exception {
        return cohort("/evals/auto-memory-consolidation-v1/cohort.json");
    }

    private JSONObject cohort(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            return JSON.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
