package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.MemoryScopeType;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryRetrievalCohortTest {
    private static final String RESOURCE = "/evals/auto-memory-retrieval-v1/cohort.json";
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "datasetVersion", "retrievalContractVersion",
            "privacyClassification", "qualityGate", "cases");
    private static final Set<String> GATE_FIELDS = Set.of(
            "minimumRelevantRecallAtK", "minimumDisabledRecallAtK",
            "maximumUnauthorizedRecallRate", "maximumIneligibleRecallRate");
    private static final Set<String> CASE_FIELDS = Set.of(
            "id", "tags", "queryText", "authorizedScopes", "topK",
            "candidates", "expectedRelevantIds");
    private static final Set<String> SCOPE_FIELDS = Set.of(
            "ownerKey", "scopeType", "scopeKey");
    private static final Set<String> CANDIDATE_FIELDS = Set.of(
            "id", "ownerKey", "scopeType", "scopeKey", "candidateKind",
            "candidateState", "semanticKey", "title", "canonicalText");
    private static final Set<String> REQUIRED_RISK_TAGS = Set.of(
            "challenger", "paraphrase", "en", "zh-cn", "user", "chartbook",
            "negation", "related", "different-dimension", "disabled", "opt-out",
            "scope-isolation", "tenant-isolation", "lifecycle", "superseded",
            "deleted", "current");
    private static final Set<String> CHALLENGER_STATES = Set.of(
            "CONFLICTING", "SUPERSEDED");

    @Test
    void frozenCohortCoversSemanticRecallAndIsolationRisks() throws Exception {
        JSONObject cohort = cohort();
        assertEquals(ROOT_FIELDS, cohort.keySet());
        assertEquals("AUTO_MEMORY_RETRIEVAL_CASES_V1", cohort.getString("schemaVersion"));
        assertEquals("auto-memory-retrieval-v1", cohort.getString("datasetVersion"));
        assertEquals("AUTO_MEMORY_RETRIEVAL_V1", cohort.getString("retrievalContractVersion"));
        assertEquals("synthetic", cohort.getString("privacyClassification"));
        JSONObject gate = cohort.getJSONObject("qualityGate");
        assertEquals(GATE_FIELDS, gate.keySet());
        assertEquals(0.95d, gate.getDoubleValue("minimumRelevantRecallAtK"));
        assertEquals(1.0d, gate.getDoubleValue("minimumDisabledRecallAtK"));
        assertEquals(0.0d, gate.getDoubleValue("maximumUnauthorizedRecallRate"));
        assertEquals(0.0d, gate.getDoubleValue("maximumIneligibleRecallRate"));

        Set<String> caseIds = new HashSet<>();
        Set<String> tags = new HashSet<>();
        int disabledExpectations = 0;
        int unauthorizedDistractors = 0;
        int ineligibleDistractors = 0;
        JSONArray cases = cohort.getJSONArray("cases");
        assertEquals(8, cases.size());

        for (Object rawCase : cases) {
            JSONObject testCase = (JSONObject) rawCase;
            assertEquals(CASE_FIELDS, testCase.keySet());
            assertTrue(caseIds.add(requiredText(testCase, "id")), "case ids must be unique");
            requiredText(testCase, "queryText");
            tags.addAll(testCase.getJSONArray("tags").toJavaList(String.class));

            int topK = testCase.getIntValue("topK");
            assertTrue(topK >= 1 && topK <= 16, testCase.getString("id"));
            List<ScopeRef> authorizedScopes = scopes(testCase);
            assertFalse(authorizedScopes.isEmpty(), testCase.getString("id"));

            Map<String, Candidate> candidates = candidates(testCase);
            List<String> expectedIds = testCase.getJSONArray("expectedRelevantIds")
                    .toJavaList(String.class);
            assertFalse(expectedIds.isEmpty(), testCase.getString("id"));
            assertTrue(expectedIds.size() <= topK, testCase.getString("id"));
            assertEquals(expectedIds.size(), new HashSet<>(expectedIds).size());

            for (Candidate candidate : candidates.values()) {
                if (!authorizedScopes.contains(candidate.scope())) {
                    unauthorizedDistractors++;
                }
                if (!candidate.eligible()) {
                    ineligibleDistractors++;
                }
            }
            for (String expectedId : expectedIds) {
                Candidate expected = candidates.get(expectedId);
                assertNotNull(expected, testCase.getString("id") + ": " + expectedId);
                assertTrue(authorizedScopes.contains(expected.scope()), expectedId);
                assertTrue(expected.eligible(), expectedId);
                if ("DISABLED".equals(expected.state())) {
                    disabledExpectations++;
                }
            }
        }

        assertTrue(tags.containsAll(REQUIRED_RISK_TAGS));
        assertTrue(disabledExpectations > 0, "disabled Memory must remain consolidatable");
        assertTrue(unauthorizedDistractors > 0, "scope and tenant leaks need negative fixtures");
        assertTrue(ineligibleDistractors > 0, "terminal lifecycle states need negative fixtures");
    }

    @Test
    void qualityGateRejectsMissesLeaksAndTerminalCandidates() throws Exception {
        JSONObject cohort = cohort();
        Map<String, List<String>> perfect = expectedRankings(cohort);
        assertTrue(evaluate(cohort, perfect).passes(cohort.getJSONObject("qualityGate")));

        Map<String, List<String>> withMiss = mutableRankings(perfect);
        withMiss.get("en-challenger-paraphrase").clear();
        assertFalse(evaluate(cohort, withMiss).passes(cohort.getJSONObject("qualityGate")));

        Map<String, List<String>> withScopeLeak = mutableRankings(perfect);
        withScopeLeak.get("scope-filter-precedes-ranking").add("book-2-layout-direction");
        assertFalse(evaluate(cohort, withScopeLeak).passes(cohort.getJSONObject("qualityGate")));

        Map<String, List<String>> withTerminalCandidate = mutableRankings(perfect);
        withTerminalCandidate.get("superseded-and-deleted-are-ineligible")
                .add("superseded-concise-challenger");
        assertFalse(evaluate(cohort, withTerminalCandidate)
                .passes(cohort.getJSONObject("qualityGate")));
    }

    private RetrievalMetrics evaluate(
            JSONObject cohort,
            Map<String, List<String>> rankings
    ) {
        int expectedRelevant = 0;
        int retrievedRelevant = 0;
        int expectedDisabled = 0;
        int retrievedDisabled = 0;
        int returned = 0;
        int unauthorized = 0;
        int ineligible = 0;

        for (Object rawCase : cohort.getJSONArray("cases")) {
            JSONObject testCase = (JSONObject) rawCase;
            String caseId = testCase.getString("id");
            List<String> ranking = rankings.getOrDefault(caseId, List.of());
            assertTrue(ranking.size() <= testCase.getIntValue("topK"), caseId);
            assertEquals(ranking.size(), new HashSet<>(ranking).size(), caseId);

            Map<String, Candidate> candidates = candidates(testCase);
            Set<String> expected = Set.copyOf(testCase.getJSONArray("expectedRelevantIds")
                    .toJavaList(String.class));
            List<ScopeRef> authorizedScopes = scopes(testCase);
            expectedRelevant += expected.size();

            for (String expectedId : expected) {
                Candidate candidate = candidates.get(expectedId);
                if ("DISABLED".equals(candidate.state())) {
                    expectedDisabled++;
                }
            }
            for (String candidateId : ranking) {
                Candidate candidate = candidates.get(candidateId);
                assertNotNull(candidate, caseId + ": unknown candidate " + candidateId);
                returned++;
                if (expected.contains(candidateId)) {
                    retrievedRelevant++;
                    if ("DISABLED".equals(candidate.state())) {
                        retrievedDisabled++;
                    }
                }
                if (!authorizedScopes.contains(candidate.scope())) {
                    unauthorized++;
                }
                if (!candidate.eligible()) {
                    ineligible++;
                }
            }
        }
        return new RetrievalMetrics(
                ratio(retrievedRelevant, expectedRelevant),
                ratio(retrievedDisabled, expectedDisabled),
                ratio(unauthorized, returned),
                ratio(ineligible, returned));
    }

    private Map<String, Candidate> candidates(JSONObject testCase) {
        Map<String, Candidate> candidates = new HashMap<>();
        for (Object rawCandidate : testCase.getJSONArray("candidates")) {
            JSONObject value = (JSONObject) rawCandidate;
            assertEquals(CANDIDATE_FIELDS, value.keySet());
            Candidate candidate = new Candidate(
                    requiredText(value, "id"),
                    scope(value),
                    requiredText(value, "candidateKind"),
                    requiredText(value, "candidateState"));
            requiredText(value, "semanticKey");
            requiredText(value, "title");
            requiredText(value, "canonicalText");
            assertTrue(candidate.validLifecycle(), candidate.id());
            assertTrue(candidates.put(candidate.id(), candidate) == null,
                    "candidate ids must be unique within a case");
        }
        return candidates;
    }

    private List<ScopeRef> scopes(JSONObject testCase) {
        List<ScopeRef> scopes = new ArrayList<>();
        for (Object rawScope : testCase.getJSONArray("authorizedScopes")) {
            JSONObject value = (JSONObject) rawScope;
            assertEquals(SCOPE_FIELDS, value.keySet());
            ScopeRef scope = scope(value);
            assertFalse(scopes.contains(scope), "authorized scopes must be unique");
            scopes.add(scope);
        }
        return List.copyOf(scopes);
    }

    private ScopeRef scope(JSONObject value) {
        String ownerKey = requiredText(value, "ownerKey");
        MemoryScopeType type = MemoryScopeType.valueOf(requiredText(value, "scopeType"));
        String scopeKey = requiredText(value, "scopeKey");
        if (type == MemoryScopeType.USER) {
            assertEquals(ownerKey, scopeKey, "USER scopeKey must equal ownerKey");
        }
        return new ScopeRef(ownerKey, type, scopeKey);
    }

    private Map<String, List<String>> expectedRankings(JSONObject cohort) {
        Map<String, List<String>> rankings = new LinkedHashMap<>();
        for (Object rawCase : cohort.getJSONArray("cases")) {
            JSONObject testCase = (JSONObject) rawCase;
            rankings.put(
                    testCase.getString("id"),
                    testCase.getJSONArray("expectedRelevantIds").toJavaList(String.class));
        }
        return rankings;
    }

    private Map<String, List<String>> mutableRankings(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, new ArrayList<>(value)));
        return copy;
    }

    private JSONObject cohort() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(input);
            return JSON.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String requiredText(JSONObject value, String field) {
        String text = value.getString(field);
        assertNotNull(text, field);
        assertFalse(text.isBlank(), field);
        return text;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0d : (double) numerator / denominator;
    }

    private record ScopeRef(String ownerKey, MemoryScopeType scopeType, String scopeKey) {
    }

    private record Candidate(String id, ScopeRef scope, String kind, String state) {
        private boolean validLifecycle() {
            if ("CURRENT".equals(kind)) {
                try {
                    AutoMemoryStatus.valueOf(state);
                    return true;
                } catch (IllegalArgumentException ignored) {
                    return false;
                }
            }
            return "CHALLENGER".equals(kind) && CHALLENGER_STATES.contains(state);
        }

        private boolean eligible() {
            return !Set.of("DELETED", "SUPERSEDED").contains(state);
        }
    }

    private record RetrievalMetrics(
            double relevantRecallAtK,
            double disabledRecallAtK,
            double unauthorizedRecallRate,
            double ineligibleRecallRate
    ) {
        private boolean passes(JSONObject gate) {
            // Safety boundaries are gates, not ranking tradeoffs.
            return relevantRecallAtK >= gate.getDoubleValue("minimumRelevantRecallAtK")
                    && disabledRecallAtK >= gate.getDoubleValue("minimumDisabledRecallAtK")
                    && unauthorizedRecallRate <= gate.getDoubleValue(
                            "maximumUnauthorizedRecallRate")
                    && ineligibleRecallRate <= gate.getDoubleValue(
                            "maximumIneligibleRecallRate");
        }
    }
}
