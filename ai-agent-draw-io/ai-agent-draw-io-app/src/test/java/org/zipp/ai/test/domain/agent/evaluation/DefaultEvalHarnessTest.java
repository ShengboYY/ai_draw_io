package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;
import org.zipp.ai.domain.agent.service.evaluation.DefaultEvalHarness;
import org.zipp.ai.domain.agent.service.evaluation.EvalCaseLoader;

import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DefaultEvalHarnessTest {

    @Test
    public void shouldLoadTheFinalSyntheticCaseSchema() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/evals/core-v1/edit-api-gateway.yaml")) {
            EvalCaseDefinition evalCase = new EvalCaseLoader().load(input);

            assertEquals("edit-api-gateway-001", evalCase.getCaseId());
            assertEquals("core-v1", evalCase.getDatasetVersion());
            assertEquals("synthetic", evalCase.getPrivacy().getClassification());
            assertEquals("edit_existing", evalCase.getExpected().getRouteType());
        }
    }

    @Test
    public void shouldPassRecordedTraceFixtureWhenPolicyAndCanvasAreValid() {
        EvalHarnessResult result = new DefaultEvalHarness().evaluate(validExecution());

        assertTrue(result.isPassed());
        assertEquals("core-v1", result.getCaseVersion());
        assertEquals("abc123", result.getGitSha());
        assertEquals(3, result.getGraders().size());
        assertTrue(result.getGraders().stream().allMatch(grader -> grader.getGraderVersion() != null));
    }

    @Test
    public void shouldFailWhenMutationHappensBeforeTheRequiredSkillLookup() {
        EvalExecution execution = validExecution();
        execution.getTrace().setToolCalls(List.of(
                EvalTrace.ToolCall.builder().name("modify_diagram").status(EvalTrace.RunStatus.SUCCESS).build(),
                EvalTrace.ToolCall.builder().name("get_drawio_skill").status(EvalTrace.RunStatus.SUCCESS).build()));

        EvalHarnessResult result = new DefaultEvalHarness().evaluate(execution);

        assertFalse(result.isPassed());
        assertTrue(result.getGraders().stream()
                .filter(grader -> "route_tool_policy".equals(grader.getGraderName()))
                .flatMap(grader -> grader.getEvidence().stream())
                .anyMatch(message -> message.contains("before mutation")));
    }

    @Test
    public void shouldFailWhenAMutationCaseHasNoMutationToolCall() {
        EvalExecution execution = validExecution();
        execution.getTrace().setToolCalls(List.of(
                EvalTrace.ToolCall.builder().name("get_drawio_skill").status(EvalTrace.RunStatus.SUCCESS).build()));

        EvalHarnessResult result = new DefaultEvalHarness().evaluate(execution);

        assertFalse(result.isPassed());
        assertTrue(result.getGraders().stream()
                .filter(grader -> "route_tool_policy".equals(grader.getGraderName()))
                .flatMap(grader -> grader.getEvidence().stream())
                .anyMatch(message -> message.contains("No mutation tool")));
    }

    @Test
    public void shouldFailInvalidXmlInTheDeterministicAnalyzer() {
        EvalExecution execution = validExecution();
        execution.setFinalCanvasXml("<mxGraphModel><root><mxCell id='0'></root>");

        EvalHarnessResult result = new DefaultEvalHarness().evaluate(execution);

        assertFalse(result.isPassed());
        assertNotNull(result.getGraders().stream()
                .filter(grader -> "xml_integrity".equals(grader.getGraderName()))
                .findFirst()
                .orElse(null));
    }

    private EvalExecution validExecution() {
        EvalCaseDefinition.Expected expected = new EvalCaseDefinition.Expected();
        expected.setRouteType("edit_existing");
        expected.setTaskOutcome(EvalTrace.TaskOutcome.FULFILLED);
        expected.setRequiredToolNamesBeforeMutation(List.of("get_drawio_skill"));
        expected.setAllowedMutationTools(List.of("modify_diagram"));
        expected.setRequireCanvasChange(true);
        expected.setMaxCriticalIssues(0);
        expected.setMaxMajorIssues(0);

        EvalCaseDefinition evalCase = EvalCaseDefinition.builder()
                .caseId("edit-api-gateway-001")
                .datasetVersion("core-v1")
                .origin("specification-derived")
                .diagramType("architecture")
                .privacy(new EvalCaseDefinition.Privacy("synthetic", null))
                .expected(expected)
                .build();
        EvalTrace trace = EvalTrace.builder()
                .runStatus(EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(EvalTrace.TaskOutcome.FULFILLED)
                .routing(EvalTrace.Routing.builder().routeType("edit_existing").diagramType("architecture").build())
                .toolCalls(List.of(
                        EvalTrace.ToolCall.builder().name("get_drawio_skill").status(EvalTrace.RunStatus.SUCCESS).build(),
                        EvalTrace.ToolCall.builder().name("modify_diagram").status(EvalTrace.RunStatus.SUCCESS).build()))
                .beforeCanvasHash("before")
                .afterCanvasHash("after")
                .build();
        return EvalExecution.builder()
                .evalCase(evalCase)
                .trace(trace)
                .gitSha("abc123")
                .finalCanvasXml(cleanCanvasXml())
                .build();
    }

    private String cleanCanvasXml() {
        return "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='Gateway' vertex='1' parent='1'>"
                + "<mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>";
    }
}
