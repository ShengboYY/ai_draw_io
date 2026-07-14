package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioSkillToolNames;
import org.zipp.ai.domain.agent.service.evaluation.DefaultEvalHarness;
import org.zipp.ai.domain.agent.service.evaluation.ModeBReplayExecutionFactory;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/** Mode B: each case owns the recorded router and tool inputs used by its deterministic replay. */
public class StubbedEvalReplayTest {

    @Test
    public void shouldReplayTheCaseOwnedRouterReplyAndCanvasToolInput() throws Exception {
        EvalCaseDefinition evalCase = EvalCaseDefinition.builder()
                .caseId("edit-payment-service-stubbed-001")
                .caseVersion("1")
                .datasetVersion("core-v1")
                .diagramType("architecture")
                .origin("specification-derived")
                .risk("high")
                .fixtureVersion("fixture-v1")
                .input(Map.of("user", "Add Payment Service and connect it to Order Service"))
                .privacy(new EvalCaseDefinition.Privacy("synthetic", "none"))
                .provenance(EvalCaseDefinition.Provenance.builder().sourceTraceRetained(false)
                        .reviewer("eval-team").approvedAt("2026-07-11T00:00:00Z").build())
                .executionProfile(profile())
                .expected(expected())
                .replay(replay())
                .build();

        EvalExecution execution = new ModeBReplayExecutionFactory().create(evalCase);

        assertTrue(execution.getFinalCanvasXml().contains("Payment Service"));
        assertTrue(new DefaultEvalHarness().evaluate(execution).isPassed());
    }

    private EvalCaseDefinition.Expected expected() {
        EvalCaseDefinition.Expected expected = new EvalCaseDefinition.Expected();
        expected.setRouteType("edit_existing");
        expected.setRouteDiagramType("architecture");
        expected.setSkillName("none");
        expected.setTaskOutcome(EvalTrace.TaskOutcome.FULFILLED);
        expected.setRequiredToolNamesBeforeMutation(List.of(DrawioSkillToolNames.GET_DRAWIO_SKILL));
        expected.setAllowedMutationTools(List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM));
        expected.setRequireCanvasChange(true);
        expected.setMaxCriticalIssues(0);
        expected.setMaxMajorIssues(0);
        return expected;
    }

    private EvalCaseDefinition.Replay replay() {
        return EvalCaseDefinition.Replay.builder()
                .initialCanvasXml("<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                        + "<mxCell id='2' value='Order Service' vertex='1' parent='1'>"
                        + "<mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>"
                        + "</root></mxGraphModel>")
                .routerReply("{\"routeType\":\"edit_existing\",\"diagramType\":\"architecture\","
                        + "\"skillName\":\"none\",\"answer\":\"\",\"reason\":\"add payment service\"}")
                .toolCalls(List.of(EvalCaseDefinition.ReplayToolCall.builder()
                        .name(DrawioCanvasToolNames.MODIFY_DIAGRAM)
                        .mode("append")
                        .cells("<mxCell id='3' value='Payment Service' vertex='1' parent='1'>"
                                + "<mxGeometry x='340' y='100' width='140' height='60' as='geometry'/></mxCell>"
                                + "<mxCell id='4' value='charges' edge='1' parent='1' source='2' target='3'>"
                                + "<mxGeometry relative='1' as='geometry'/></mxCell>")
                        .build()))
                .taskOutcome(EvalTrace.TaskOutcome.FULFILLED)
                .build();
    }

    private EvalCaseDefinition.ExecutionProfile profile() {
        return EvalCaseDefinition.ExecutionProfile.builder()
                .profileId("phase1-mode-b")
                .model("stubbed")
                .promptConfigHash("prompt-v1")
                .skillCatalogHash("skills-v1")
                .toolPolicyVersion("tool-policy-v1")
                .build();
    }
}
