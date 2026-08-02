package org.zipp.ai.infrastructure.turn.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryRetrievalCohortTest {

    @Test
    void frozenCohortMatchesProductionScopesAndCoversRetrievalRisks() throws Exception {
        AutoMemoryRetrievalCohort.Dataset dataset = AutoMemoryRetrievalCohort.load();

        assertEquals("AUTO_MEMORY_RETRIEVAL_CASES_V1", dataset.schemaVersion());
        assertEquals("auto-memory-retrieval-v2", dataset.datasetVersion());
        assertEquals("AUTO_MEMORY_RETRIEVAL_V2", dataset.retrievalContractVersion());
        assertEquals("synthetic", dataset.privacyClassification());
        assertEquals(8, dataset.cases().size());
        assertEquals(0.95d, dataset.gate().minimumRelevantRecallAtK());
        assertEquals(1.0d, dataset.gate().minimumDisabledRecallAtK());
        assertEquals(0.0d, dataset.gate().maximumUnauthorizedRecallRate());
        assertEquals(0.0d, dataset.gate().maximumIneligibleRecallRate());

        Set<String> tags = new HashSet<>();
        int disabledExpectations = 0;
        int unauthorizedDistractors = 0;
        int ineligibleDistractors = 0;
        Map<String, AutoMemoryRetrievalCohort.Candidate> candidates = dataset.candidatesById();
        for (AutoMemoryRetrievalCohort.EvaluationCase testCase : dataset.cases()) {
            tags.addAll(testCase.tags());
            Set<String> expected = Set.copyOf(testCase.expectedRelevantIds());
            for (String candidateId : expected) {
                if (candidates.get(candidateId).disabled()) {
                    disabledExpectations++;
                }
            }
            for (AutoMemoryRetrievalCohort.Candidate candidate : testCase.candidates()) {
                if (!testCase.authorizedScopes().contains(candidate.scope())) {
                    unauthorizedDistractors++;
                }
                if (!candidate.eligible()) {
                    ineligibleDistractors++;
                }
            }
        }

        assertTrue(tags.containsAll(AutoMemoryRetrievalCohort.REQUIRED_RISK_TAGS));
        assertTrue(disabledExpectations > 0, "disabled Memory must remain consolidatable");
        assertTrue(unauthorizedDistractors > 0, "scope and tenant leaks need negative fixtures");
        assertTrue(ineligibleDistractors > 0, "terminal lifecycle states need negative fixtures");
    }

    @Test
    void qualityGateRejectsMissesLeaksAndTerminalCandidates() throws Exception {
        AutoMemoryRetrievalCohort.Dataset dataset = AutoMemoryRetrievalCohort.load();
        Map<String, List<String>> perfect = AutoMemoryRetrievalCohort.expectedRankings(dataset);
        assertTrue(AutoMemoryRetrievalCohort.evaluate(dataset, perfect).passes(dataset.gate()));

        Map<String, List<String>> withMiss = mutableRankings(perfect);
        withMiss.get("en-challenger-paraphrase").clear();
        assertFalse(AutoMemoryRetrievalCohort.evaluate(dataset, withMiss).passes(dataset.gate()));

        Map<String, List<String>> withScopeLeak = mutableRankings(perfect);
        withScopeLeak.get("scope-filter-precedes-ranking").add("book-2-layout-direction");
        assertFalse(AutoMemoryRetrievalCohort.evaluate(
                dataset, withScopeLeak).passes(dataset.gate()));

        Map<String, List<String>> withTerminalCandidate = mutableRankings(perfect);
        withTerminalCandidate.get("superseded-and-deleted-are-ineligible")
                .add("superseded-concise-challenger");
        assertFalse(AutoMemoryRetrievalCohort.evaluate(
                dataset, withTerminalCandidate).passes(dataset.gate()));
    }

    private Map<String, List<String>> mutableRankings(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, new ArrayList<>(value)));
        return copy;
    }
}
