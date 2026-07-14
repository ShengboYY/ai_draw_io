package org.zipp.ai.test.domain.agent.evaluation;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueSeverity;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualRepairScope;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.IIntentRoutingService;
import org.zipp.ai.domain.agent.service.evaluation.DefaultEvalHarness;
import org.zipp.ai.trigger.evaluation.ProductionDrawingLiveEvalAdapter;
import org.zipp.ai.trigger.evaluation.ProductionRouterLiveEvalAdapter;
import org.zipp.ai.trigger.evaluation.ProductionVisualReviewLiveEvalAdapter;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ProductionTargetLiveEvalAdaptersTest {
    private static final String EMPTY = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>";
    private static final String DRAWN = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/><mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell></root></mxGraphModel>";

    @Test
    public void routerLiveAdapterCallsOnlyRouterBoundary() {
        IIntentRoutingService router = command -> routeResult();
        EvalCaseDefinition evalCase = evalCase("Improve this", "none");
        evalCase.getExpected().setRouteType("clarify");
        evalCase.getExpected().setTaskOutcome(org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace.TaskOutcome.CLARIFICATION_NEEDED);

        var execution = new ProductionRouterLiveEvalAdapter(router, "gpt-5.5", "r4-live").execute(evalCase);

        assertEquals("clarify", execution.getTrace().getRouting().getRouteType());
        assertTrue(new DefaultEvalHarness().evaluate(execution).isPassed());
        assertTrue(execution.getTrace().getToolCalls().isEmpty());
        assertEquals(EMPTY, execution.getFinalCanvasXml());
    }

    @Test
    public void drawingLiveAdapterCallsDrawingBoundaryAndExtractsCanvasArtifact() {
        EvalCaseDefinition evalCase = evalCase("Draw an API service", "architecture");
        evalCase.getExpected().setRequireCanvasChange(true);
        evalCase.getExpected().setAllowedMutationTools(List.of("modify_diagram"));
        String cells = "<mxCell id='2' value='API' vertex='1' parent='1'><mxGeometry x='100' y='100' width='120' height='60' as='geometry'/></mxCell>";
        IChatService drawing = new FakeChat(List.of("{\"type\":\"patch_cells\",\"cells\":"
                + com.alibaba.fastjson.JSON.toJSONString(cells)
                + ",\"toolName\":\"modify_diagram\",\"toolStatus\":\"SUCCESS\"}"));

        var execution = new ProductionDrawingLiveEvalAdapter(drawing, "300000", "gpt-5.5", "r4-live")
                .execute(evalCase);

        assertTrue(execution.getFinalCanvasXml().contains("value=\"API\""));
        assertNull(execution.getTrace().getRouting());
        assertEquals("modify_diagram", execution.getTrace().getToolCalls().get(0).getName());
        assertTrue(new DefaultEvalHarness().evaluate(execution).isPassed());
    }

    @Test
    public void drawingLiveAdapterNeverSynthesizesExpectedToolEvidence() {
        EvalCaseDefinition evalCase = evalCase("Draw an API service", "architecture");
        evalCase.getExpected().setRequireCanvasChange(true);
        evalCase.getExpected().setAllowedMutationTools(List.of("modify_diagram"));
        IChatService wrongTool = new FakeChat(List.of("{\"type\":\"drawio_done\",\"content\":"
                + com.alibaba.fastjson.JSON.toJSONString(DRAWN)
                + ",\"toolName\":\"create_diagram\",\"toolStatus\":\"SUCCESS\"}"));

        var execution = new ProductionDrawingLiveEvalAdapter(wrongTool, "300000", "gpt-5.5", "r4-live")
                .execute(evalCase);

        assertEquals("create_diagram", execution.getTrace().getToolCalls().get(0).getName());
        assertFalse(new DefaultEvalHarness().evaluate(execution).isPassed());
    }

    @Test
    public void drawingLiveAdapterKeepsObservedToolFailure() {
        EvalCaseDefinition evalCase = evalCase("Draw an API service", "architecture");
        evalCase.getExpected().setRequireCanvasChange(true);
        evalCase.getExpected().setAllowedMutationTools(List.of("modify_diagram"));
        IChatService rejected = new FakeChat(List.of("{\"type\":\"tool_error\","
                + "\"message\":\"unsupported mode\",\"toolName\":\"modify_diagram\",\"toolStatus\":\"FAILED\"}"));

        var execution = new ProductionDrawingLiveEvalAdapter(rejected, "300000", "gpt-5.5", "r4-live")
                .execute(evalCase);

        assertEquals(org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace.RunStatus.FAILED,
                execution.getTrace().getToolCalls().get(0).getStatus());
        assertEquals(EMPTY, execution.getFinalCanvasXml());
        assertFalse(new DefaultEvalHarness().evaluate(execution).isPassed());
    }

    @Test
    public void visualReviewLiveAdapterCallsProductionReviewerAndGradesObservedIssues() {
        EvalCaseDefinition evalCase = evalCase("Make the API label readable", "architecture");
        evalCase.setEvaluationTarget(EvaluationTarget.VISUAL_REVIEW);
        evalCase.setInput(Map.of(
                "user", "Make the API label readable",
                "stage", "POST_MUTATION",
                "afterImageDataUrl", "data:image/png;base64,AAAA"));
        evalCase.getExpected().setReviewAvailable(true);
        evalCase.getExpected().setReviewDecision("REPAIR");
        evalCase.getExpected().setRequiredVisualIssueTypes(List.of("TEXT_READABILITY"));
        var reviewer = (org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer) command ->
                CanvasVisualReviewResult.builder().available(true).summary("API is too small")
                        .issues(List.of(CanvasVisualIssue.builder()
                                .type(CanvasVisualIssueType.TEXT_READABILITY)
                                .severity(CanvasVisualIssueSeverity.MAJOR)
                                .region("center").evidence("small").repairInstruction("increase size")
                                .repairScope(CanvasVisualRepairScope.LOCAL).build()))
                        .reviewerVersion("production-reviewer-v1").build();

        var execution = new ProductionVisualReviewLiveEvalAdapter(reviewer, "gpt-5.5", 1D, "r4-live")
                .execute(evalCase);

        assertEquals("300018", execution.getTrace().getSteps().get(0).getAgentId());
        assertEquals("REPAIR", execution.getTrace().getVisualReview().getDecision());
        assertEquals(List.of("TEXT_READABILITY"), execution.getTrace().getVisualReview().getIssueTypes());
        assertTrue(new DefaultEvalHarness().evaluate(execution).isPassed());
    }

    @Test
    public void visualReviewLiveAdapterRejectsFrozenTemperatureMismatch() {
        EvalCaseDefinition evalCase = evalCase("Review this", "architecture");
        evalCase.setEvaluationTarget(EvaluationTarget.VISUAL_REVIEW);
        evalCase.setInput(Map.of("user", "Review this", "afterImageDataUrl", "data:image/png;base64,AAAA"));
        evalCase.getExecutionProfile().setTemperature(0D);
        var reviewer = (org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer) command ->
                CanvasVisualReviewResult.builder().available(true).issues(List.of()).build();

        var adapter = new ProductionVisualReviewLiveEvalAdapter(reviewer, "gpt-5.5", 1D, "r4-live");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> adapter.execute(evalCase));
        assertTrue(error.getMessage().contains("temperature"));
    }

    private EvalCaseDefinition evalCase(String user, String diagramType) {
        return EvalCaseDefinition.builder().caseId("live-target").caseVersion("1").diagramType(diagramType)
                .input(Map.of("user", user)).replay(EvalCaseDefinition.Replay.builder().initialCanvasXml(EMPTY).build())
                .expected(new EvalCaseDefinition.Expected()).executionProfile(EvalCaseDefinition.ExecutionProfile.builder()
                        .model("gpt-5.5").build()).build();
    }

    private IntentRoutingResult routeResult() {
        IntentRoutingResult result = new IntentRoutingResult();
        result.setRouteType("clarify"); result.setDiagramType("none"); result.setSkillName("none");
        result.setAnswer("Which part?");
        return result;
    }

    private record FakeChat(List<String> replies) implements IChatService {
        @Override public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() { return List.of(); }
        @Override public String createSession(String agentId, String userId) { return "session"; }
        @Override public String ensureSession(String agentId, String userId, String sessionId) { return sessionId; }
        @Override public List<String> handleMessage(String agentId, String userId, String message) { return replies; }
        @Override public List<String> handleMessage(String agentId, String userId, String sessionId, String message) { return replies; }
        @Override public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) { return Flowable.empty(); }
        @Override public List<String> handleMessage(ChatCommandEntity command) { return replies; }
    }
}
