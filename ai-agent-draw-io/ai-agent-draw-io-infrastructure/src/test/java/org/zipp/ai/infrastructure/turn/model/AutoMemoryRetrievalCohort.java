package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.MemoryScopeType;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict loader and shared quality gate for the frozen semantic-retrieval cohort. */
final class AutoMemoryRetrievalCohort {
    static final String RESOURCE = "/evals/auto-memory-retrieval-v2/cohort.json";
    static final Set<String> REQUIRED_RISK_TAGS = Set.of(
            "challenger", "paraphrase", "en", "zh-cn", "user", "chartbook",
            "negation", "related", "different-dimension", "disabled", "opt-out",
            "scope-isolation", "tenant-isolation", "lifecycle", "superseded",
            "deleted", "current");

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
    private static final Set<String> CHALLENGER_STATES = Set.of(
            "CONFLICTING", "SUPERSEDED");

    private AutoMemoryRetrievalCohort() {
    }

    static Dataset load() throws Exception {
        JSONObject root;
        try (InputStream input = AutoMemoryRetrievalCohort.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Auto Memory retrieval cohort is missing");
            }
            root = JSON.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        exactFields(root, ROOT_FIELDS, "root");
        JSONObject gateValue = root.getJSONObject("qualityGate");
        exactFields(gateValue, GATE_FIELDS, "qualityGate");
        Gate gate = new Gate(
                gateValue.getDoubleValue("minimumRelevantRecallAtK"),
                gateValue.getDoubleValue("minimumDisabledRecallAtK"),
                gateValue.getDoubleValue("maximumUnauthorizedRecallRate"),
                gateValue.getDoubleValue("maximumIneligibleRecallRate"));

        List<EvaluationCase> cases = new ArrayList<>();
        Set<String> caseIds = new HashSet<>();
        Set<String> candidateIds = new HashSet<>();
        for (Object rawCase : root.getJSONArray("cases")) {
            JSONObject value = (JSONObject) rawCase;
            exactFields(value, CASE_FIELDS, "case");
            String id = requiredText(value, "id");
            if (!caseIds.add(id)) {
                throw new IllegalArgumentException("duplicate retrieval case id: " + id);
            }
            List<ScopeRef> scopes = scopes(value.getJSONArray("authorizedScopes"));
            validateProductionScopes(id, scopes);
            int topK = value.getIntValue("topK");
            if (topK < 1 || topK > 16) {
                throw new IllegalArgumentException("invalid topK for " + id);
            }
            List<Candidate> candidates = candidates(value.getJSONArray("candidates"), candidateIds);
            List<String> expectedIds = List.copyOf(
                    value.getJSONArray("expectedRelevantIds").toJavaList(String.class));
            if (expectedIds.isEmpty() || expectedIds.size() > topK
                    || expectedIds.size() != new HashSet<>(expectedIds).size()) {
                throw new IllegalArgumentException("invalid expected ranking for " + id);
            }
            Map<String, Candidate> local = new LinkedHashMap<>();
            candidates.forEach(candidate -> local.put(candidate.id(), candidate));
            for (String expectedId : expectedIds) {
                Candidate expected = local.get(expectedId);
                if (expected == null || !expected.eligible() || !scopes.contains(expected.scope())) {
                    throw new IllegalArgumentException("invalid expected candidate: " + expectedId);
                }
            }
            cases.add(new EvaluationCase(
                    id,
                    List.copyOf(value.getJSONArray("tags").toJavaList(String.class)),
                    requiredText(value, "queryText"),
                    scopes,
                    topK,
                    candidates,
                    expectedIds));
        }
        return new Dataset(
                requiredText(root, "schemaVersion"),
                requiredText(root, "datasetVersion"),
                requiredText(root, "retrievalContractVersion"),
                requiredText(root, "privacyClassification"),
                gate,
                List.copyOf(cases));
    }

    static Metrics evaluate(Dataset dataset, Map<String, List<String>> rankings) {
        Map<String, Candidate> allCandidates = dataset.candidatesById();
        int expectedRelevant = 0;
        int retrievedRelevant = 0;
        int expectedDisabled = 0;
        int retrievedDisabled = 0;
        int top1Relevant = 0;
        double reciprocalRanks = 0.0d;
        int returned = 0;
        int unauthorized = 0;
        int ineligible = 0;
        int unknown = 0;

        for (EvaluationCase testCase : dataset.cases()) {
            List<String> ranking = List.copyOf(rankings.getOrDefault(testCase.id(), List.of()));
            if (ranking.size() > testCase.topK()
                    || ranking.size() != new HashSet<>(ranking).size()) {
                throw new IllegalArgumentException("invalid actual ranking for " + testCase.id());
            }
            Set<String> expected = Set.copyOf(testCase.expectedRelevantIds());
            expectedRelevant += expected.size();
            int firstRelevantRank = -1;
            for (int index = 0; index < ranking.size(); index++) {
                if (expected.contains(ranking.get(index))) {
                    firstRelevantRank = index;
                    break;
                }
            }
            if (firstRelevantRank == 0) {
                top1Relevant++;
            }
            if (firstRelevantRank >= 0) {
                reciprocalRanks += 1.0d / (firstRelevantRank + 1);
            }
            for (String expectedId : expected) {
                if (allCandidates.get(expectedId).disabled()) {
                    expectedDisabled++;
                }
            }
            for (String candidateId : ranking) {
                returned++;
                Candidate candidate = allCandidates.get(candidateId);
                if (candidate == null) {
                    unknown++;
                    continue;
                }
                if (expected.contains(candidateId)) {
                    retrievedRelevant++;
                    if (candidate.disabled()) {
                        retrievedDisabled++;
                    }
                }
                if (!testCase.authorizedScopes().contains(candidate.scope())) {
                    unauthorized++;
                }
                if (!candidate.eligible()) {
                    ineligible++;
                }
            }
        }
        return new Metrics(
                ratio(retrievedRelevant, expectedRelevant),
                ratio(retrievedDisabled, expectedDisabled),
                ratio(top1Relevant, dataset.cases().size()),
                reciprocalRanks / dataset.cases().size(),
                ratio(unauthorized, returned),
                ratio(ineligible, returned),
                unknown);
    }

    static Map<String, List<String>> expectedRankings(Dataset dataset) {
        Map<String, List<String>> rankings = new LinkedHashMap<>();
        dataset.cases().forEach(testCase -> rankings.put(
                testCase.id(), new ArrayList<>(testCase.expectedRelevantIds())));
        return rankings;
    }

    private static List<ScopeRef> scopes(JSONArray values) {
        List<ScopeRef> scopes = new ArrayList<>();
        for (Object rawScope : values) {
            JSONObject value = (JSONObject) rawScope;
            exactFields(value, SCOPE_FIELDS, "scope");
            ScopeRef scope = new ScopeRef(
                    requiredText(value, "ownerKey"),
                    MemoryScopeType.valueOf(requiredText(value, "scopeType")),
                    requiredText(value, "scopeKey"));
            if (scope.scopeType() == MemoryScopeType.USER
                    && !scope.ownerKey().equals(scope.scopeKey())) {
                throw new IllegalArgumentException("USER scope key must equal owner");
            }
            if (scopes.contains(scope)) {
                throw new IllegalArgumentException("authorized scopes must be unique");
            }
            scopes.add(scope);
        }
        return List.copyOf(scopes);
    }

    private static List<Candidate> candidates(JSONArray values, Set<String> globalIds) {
        List<Candidate> candidates = new ArrayList<>();
        for (Object rawCandidate : values) {
            JSONObject value = (JSONObject) rawCandidate;
            exactFields(value, CANDIDATE_FIELDS, "candidate");
            Candidate candidate = new Candidate(
                    requiredText(value, "id"),
                    new ScopeRef(
                            requiredText(value, "ownerKey"),
                            MemoryScopeType.valueOf(requiredText(value, "scopeType")),
                            requiredText(value, "scopeKey")),
                    requiredText(value, "candidateKind"),
                    requiredText(value, "candidateState"),
                    requiredText(value, "semanticKey"),
                    requiredText(value, "title"),
                    requiredText(value, "canonicalText"));
            if (candidate.scope().scopeType() == MemoryScopeType.USER
                    && !candidate.scope().ownerKey().equals(candidate.scope().scopeKey())) {
                throw new IllegalArgumentException("candidate USER scope key must equal owner");
            }
            if (!candidate.validLifecycle() || !globalIds.add(candidate.id())) {
                throw new IllegalArgumentException("invalid or duplicate candidate: " + candidate.id());
            }
            candidates.add(candidate);
        }
        return List.copyOf(candidates);
    }

    private static void validateProductionScopes(String caseId, List<ScopeRef> scopes) {
        if (scopes.isEmpty() || scopes.size() > 2) {
            throw new IllegalArgumentException("invalid scope count for " + caseId);
        }
        String owner = scopes.get(0).ownerKey();
        if (scopes.stream().anyMatch(scope -> !owner.equals(scope.ownerKey()))) {
            throw new IllegalArgumentException("authorized scopes must share an owner");
        }
        long userScopes = scopes.stream()
                .filter(scope -> scope.scopeType() == MemoryScopeType.USER)
                .count();
        long chartbookScopes = scopes.stream()
                .filter(scope -> scope.scopeType() == MemoryScopeType.CHARTBOOK)
                .count();
        if (userScopes != 1 || chartbookScopes != scopes.size() - 1L) {
            throw new IllegalArgumentException("scopes do not match the production query contract");
        }
    }

    private static void exactFields(JSONObject value, Set<String> expected, String location) {
        if (value == null || !expected.equals(value.keySet())) {
            throw new IllegalArgumentException("unexpected " + location + " fields");
        }
    }

    private static String requiredText(JSONObject value, String field) {
        String text = value.getString(field);
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return text;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0d : (double) numerator / denominator;
    }

    record Dataset(
            String schemaVersion,
            String datasetVersion,
            String retrievalContractVersion,
            String privacyClassification,
            Gate gate,
            List<EvaluationCase> cases
    ) {
        Map<String, Candidate> candidatesById() {
            Map<String, Candidate> candidates = new LinkedHashMap<>();
            cases.forEach(testCase -> testCase.candidates().forEach(
                    candidate -> candidates.put(candidate.id(), candidate)));
            return Map.copyOf(candidates);
        }
    }

    record Gate(
            double minimumRelevantRecallAtK,
            double minimumDisabledRecallAtK,
            double maximumUnauthorizedRecallRate,
            double maximumIneligibleRecallRate
    ) {
    }

    record EvaluationCase(
            String id,
            List<String> tags,
            String queryText,
            List<ScopeRef> authorizedScopes,
            int topK,
            List<Candidate> candidates,
            List<String> expectedRelevantIds
    ) {
        String ownerKey() {
            return authorizedScopes.get(0).ownerKey();
        }

        String chartbookScopeKey() {
            return authorizedScopes.stream()
                    .filter(scope -> scope.scopeType() == MemoryScopeType.CHARTBOOK)
                    .map(ScopeRef::scopeKey)
                    .findFirst()
                    .orElse(null);
        }
    }

    record ScopeRef(String ownerKey, MemoryScopeType scopeType, String scopeKey) {
    }

    record Candidate(
            String id,
            ScopeRef scope,
            String kind,
            String state,
            String semanticKey,
            String title,
            String canonicalText
    ) {
        boolean eligible() {
            return !Set.of("DELETED", "SUPERSEDED").contains(state);
        }

        boolean disabled() {
            return "DISABLED".equals(state);
        }

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
    }

    record Metrics(
            double relevantRecallAtK,
            double disabledRecallAtK,
            double top1RelevantRate,
            double meanReciprocalRank,
            double unauthorizedRecallRate,
            double ineligibleRecallRate,
            int unknownReturnCount
    ) {
        boolean passes(Gate gate) {
            // Isolation and lifecycle remain hard gates even if semantic recall is high.
            return relevantRecallAtK >= gate.minimumRelevantRecallAtK()
                    && disabledRecallAtK >= gate.minimumDisabledRecallAtK()
                    && unauthorizedRecallRate <= gate.maximumUnauthorizedRecallRate()
                    && ineligibleRecallRate <= gate.maximumIneligibleRecallRate()
                    && unknownReturnCount == 0;
        }
    }
}
