package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.context.AutoMemoryRecallPlanner;
import org.zipp.ai.application.turn.context.AutoMemoryRecallPlanningEligibilityPolicy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoMemoryRecallPlanningCohortTest {
    private static final List<String> RESOURCES = List.of(
            "/evals/auto-memory-recall-planning-development-v1/cohort.json",
            "/evals/auto-memory-recall-planning-v1/holdout.json",
            "/evals/auto-memory-recall-planning-development-v2/cohort.json",
            "/evals/auto-memory-recall-planning-v2/holdout.json");
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "datasetVersion", "privacyClassification", "usagePolicy",
            "qualityGate", "cases");
    private static final Set<String> CASE_FIELDS = Set.of(
            "id", "tags", "userTurn", "expectedIntentCount", "requiredIntentTerms");

    @Test
    void frozenCohortsCoverMultiIntentAndAbstentionRisks() throws Exception {
        AutoMemoryRecallPlanningEligibilityPolicy eligibility =
                new AutoMemoryRecallPlanningEligibilityPolicy();
        for (String resource : RESOURCES) {
            JSONObject cohort = cohort(resource);
            assertEquals(ROOT_FIELDS, cohort.keySet());
            assertEquals("AUTO_MEMORY_RECALL_PLANNING_CASES_V1",
                    cohort.getString("schemaVersion"));
            assertEquals("synthetic", cohort.getString("privacyClassification"));

            Set<String> ids = new HashSet<>();
            int multiIntent = 0;
            int singleIntent = 0;
            for (Object raw : cohort.getJSONArray("cases")) {
                JSONObject testCase = (JSONObject) raw;
                assertEquals(CASE_FIELDS, testCase.keySet());
                assertTrue(ids.add(testCase.getString("id")));
                assertTrue(!testCase.getString("userTurn").isBlank());
                int expected = testCase.getIntValue("expectedIntentCount");
                assertTrue(expected == 0 || expected == 2 || expected == 3);
                assertTrue(expected <= AutoMemoryRecallPlanner.MAX_SUBQUERIES);
                JSONArray terms = testCase.getJSONArray("requiredIntentTerms");
                assertEquals(expected, terms.size());
                if (expected == 0) {
                    singleIntent++;
                } else {
                    multiIntent++;
                    assertTrue(eligibility.shouldPlan(testCase.getString("userTurn")),
                            testCase.getString("id"));
                }
            }
            assertTrue(multiIntent >= 5);
            assertTrue(singleIntent >= 4);
        }
    }

    private JSONObject cohort(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            return JSON.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
