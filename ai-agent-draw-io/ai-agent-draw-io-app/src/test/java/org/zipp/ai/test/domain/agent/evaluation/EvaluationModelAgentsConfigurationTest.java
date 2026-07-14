package org.zipp.ai.test.domain.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.Test;

import java.io.InputStream;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Locks the dedicated Evaluation model agents to their tool-free, closed JSON contracts. */
public class EvaluationModelAgentsConfigurationTest {

    @Test
    public void shouldRegisterEvalDraftAgentWithSyntheticDraftContract() throws Exception {
        JsonNode table = table("drawIoEvalDraftAgent");

        assertAgent(table, "${ZIPP_EVAL_DRAFT_AGENT_ID:300013}",
                "${ZIPP_EVAL_DRAFT_MODEL_VERSION:${LLM_MODEL:gpt-5.5}}",
                "${ZIPP_EVAL_DRAFT_TEMPERATURE:0}", "agent_eval_draft");
        String instruction = instruction(table);
        assertContains(instruction, "failure_summary", "suspected_failure_family", "suggested_case",
                "user_turns", "initial_fixture_hint", "expected_route", "suggested_assertions",
                "confidence", "needs_human_review", "synthetic", "Never approve");
        JsonNode example = outputExample(instruction);
        assertEquals(Set.of("failure_summary", "suspected_failure_family", "suggested_case", "confidence", "needs_human_review"), fieldNames(example));
        assertEquals(Set.of("user_turns", "initial_fixture_hint", "expected_route", "suggested_assertions"), fieldNames(example.path("suggested_case")));
    }

    @Test
    public void shouldRegisterTextJudgeWithExactScoringContract() throws Exception {
        JsonNode table = table("drawIoEvalJudgeAgent");

        assertAgent(table, "${ZIPP_EVAL_JUDGE_AGENT_ID:300014}",
                "${ZIPP_EVAL_JUDGE_MODEL_VERSION:${LLM_MODEL:gpt-5.5}}",
                "${ZIPP_EVAL_JUDGE_TEMPERATURE:0}", "agent_eval_judge");
        String instruction = instruction(table);
        assertContains(instruction, "task_fulfilled", "helpfulness_score", "unexpected_side_effect",
                "severity", "evidence", "recommended_human_review", "severity must be none",
                "observable evidence", "Never alter");
        assertEquals(Set.of("task_fulfilled", "helpfulness_score", "unexpected_side_effect", "severity", "evidence", "recommended_human_review"),
                fieldNames(outputExample(instruction)));
    }

    @Test
    public void shouldRegisterVisualJudgeWithExactMultimodalScoringContract() throws Exception {
        JsonNode table = table("drawIoVisualEvalJudgeAgent");

        assertAgent(table, "${ZIPP_EVAL_VISUAL_JUDGE_AGENT_ID:300016}",
                "${ZIPP_EVAL_VISUAL_JUDGE_MODEL_VERSION:${VLM_MODEL:${LLM_MODEL:gpt-5.5}}}",
                "${ZIPP_EVAL_VISUAL_JUDGE_TEMPERATURE:0}", "agent_visual_eval_judge");
        assertEquals("${VLM_API_KEY:${LLM_API_KEY:}}", table.at("/module/ai-api/api-key").asText());
        String instruction = instruction(table);
        assertContains(instruction, "first image", "second image", "taskFulfilled", "readabilityScore",
                "layoutScore", "criticalIssues", "majorIssues", "evidence", "recommendedHumanReview",
                "Never alter");
        assertEquals(Set.of("taskFulfilled", "readabilityScore", "layoutScore", "criticalIssues", "majorIssues", "evidence", "recommendedHumanReview"),
                fieldNames(outputExample(instruction)));
    }

    @Test
    public void shouldRegisterVisualMinerWithClosedFindingContract() throws Exception {
        JsonNode table = table("drawIoVisualMinerAgent");

        assertAgent(table, "${ZIPP_EVAL_VISUAL_MINER_AGENT_ID:300017}",
                "${ZIPP_EVAL_VISUAL_MINER_MODEL_VERSION:${VLM_MODEL:${LLM_MODEL:gpt-5.5}}}",
                "${ZIPP_EVAL_VISUAL_MINER_TEMPERATURE:0}", "agent_visual_miner");
        assertEquals("${VLM_API_KEY:${LLM_API_KEY:}}", table.at("/module/ai-api/api-key").asText());
        String instruction = instruction(table);
        assertContains(instruction, "potentialIssue", "confidence", "issueFamily", "evidence",
                "suggestedRisk", "syntheticReconstructionSuggestion", "requiresHumanReview",
                "TEXT_TOO_SMALL", "EDGE_TRACE_DIFFICULT", "Never approve");
        assertEquals(Set.of("potentialIssue", "confidence", "issueFamily", "evidence", "suggestedRisk", "syntheticReconstructionSuggestion", "requiresHumanReview"),
                fieldNames(outputExample(instruction)));
    }

    @Test
    public void shouldExposeFailClosedRuntimeConfigurationForEvaluationAgents() throws Exception {
        JsonNode evaluation = resource("application.yml").at("/zipp/evaluation");

        assertEquals("${ZIPP_EVAL_DRAFT_AGENT_ID:300013}", evaluation.path("draft-agent-id").asText());
        assertEquals("${ZIPP_EVAL_DRAFT_MODEL_VERSION:unconfigured}", evaluation.path("draft-model-version").asText());
        assertEquals("${ZIPP_EVAL_DRAFT_TEMPERATURE:0}", evaluation.path("draft-temperature").asText());
        assertEquals("${ZIPP_EVAL_LIVE_ENABLED:false}", evaluation.path("live-enabled").asText());
        assertEquals("${ZIPP_EVAL_JUDGE_AGENT_ID:300014}", evaluation.path("judge-agent-id").asText());
        assertEquals("${ZIPP_EVAL_JUDGE_MODEL_VERSION:unconfigured}", evaluation.path("judge-model-version").asText());
        assertEquals("${ZIPP_EVAL_JUDGE_TEMPERATURE:0}", evaluation.path("judge-temperature").asText());
        assertEquals("${ZIPP_EVAL_JUDGE_CALIBRATION_APPROVED:false}", evaluation.path("judge-calibration-approved").asText());
        assertEquals("${ZIPP_EVAL_JUDGE_CALIBRATED_VERSION:unconfigured}", evaluation.path("judge-calibrated-version").asText());
        assertEquals("${ZIPP_EVAL_VISUAL_JUDGE_AGENT_ID:300016}", evaluation.path("visual-judge-agent-id").asText());
        assertEquals("${ZIPP_EVAL_VISUAL_JUDGE_MODEL_VERSION:unconfigured}", evaluation.path("visual-judge-model-version").asText());
        assertEquals("${ZIPP_EVAL_VISUAL_JUDGE_TEMPERATURE:0}", evaluation.path("visual-judge-temperature").asText());
        assertEquals("${ZIPP_EVAL_VISUAL_JUDGE_CALIBRATION_APPROVED:false}", evaluation.path("visual-judge-calibration-approved").asText());
        assertEquals("${ZIPP_EVAL_VISUAL_JUDGE_CALIBRATED_VERSION:unconfigured}", evaluation.path("visual-judge-calibrated-version").asText());
        assertEquals("${ZIPP_EVAL_VISUAL_MINER_AGENT_ID:300017}", evaluation.path("visual-miner-agent-id").asText());
        assertEquals("${ZIPP_EVAL_VISUAL_MINER_ENABLED:false}", evaluation.path("visual-miner-enabled").asText());
        assertEquals("${ZIPP_EVAL_VISUAL_MINER_TEMPERATURE:0}", evaluation.path("visual-miner-temperature").asText());
        assertEquals("${ZIPP_EVAL_VISUAL_MINER_CALIBRATION_APPROVED:false}", evaluation.path("visual-miner-calibration-approved").asText());
        assertEquals("${ZIPP_EVAL_VISUAL_MINER_CALIBRATED_VERSION:unconfigured}", evaluation.path("visual-miner-calibrated-version").asText());
    }

    private JsonNode table(String name) throws Exception {
        JsonNode table = resource("agent/agent-draw-io.yml").at("/ai/agent/config/tables/" + name);
        assertFalse(name + " must be registered", table.isMissingNode());
        return table;
    }

    private JsonNode resource(String name) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull("Configuration resource must exist: " + name, input);
            return new ObjectMapper(new YAMLFactory()).readTree(input);
        }
    }

    private void assertAgent(JsonNode table, String agentId, String model, String temperature, String agentName) {
        assertEquals(agentId, table.at("/agent/agent-id").asText());
        assertEquals(model, table.at("/module/chat-model/model").asText());
        assertEquals(temperature, table.at("/module/chat-model/temperature").asText());
        assertEquals(agentName, table.at("/module/agents/0/name").asText());
        assertEquals(agentName, table.at("/module/runner/agent-name").asText());
        // Evaluation model agents must never inherit production canvas or search tools.
        assertTrue(table.at("/module/chat-model/tool-mcp-list").isMissingNode());
    }

    private String instruction(JsonNode table) {
        return table.at("/module/agents/0/instruction").asText();
    }

    private JsonNode outputExample(String instruction) throws Exception {
        // The contract example is deliberately the final prompt line so it can be parsed and locked exactly.
        String[] lines = instruction.strip().split("\\R");
        return new ObjectMapper().readTree(lines[lines.length - 1]);
    }

    private Set<String> fieldNames(JsonNode node) {
        return Set.copyOf(node.properties().stream().map(java.util.Map.Entry::getKey).toList());
    }

    private void assertContains(String instruction, String... fragments) {
        for (String fragment : fragments) {
            assertTrue("Instruction must contain: " + fragment, instruction.contains(fragment));
        }
    }
}
