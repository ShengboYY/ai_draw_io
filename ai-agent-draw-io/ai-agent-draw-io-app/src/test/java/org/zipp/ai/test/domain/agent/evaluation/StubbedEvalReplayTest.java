package org.zipp.ai.test.domain.agent.evaluation;

import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioSkillToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.domain.agent.service.evaluation.DefaultEvalHarness;
import org.zipp.ai.domain.agent.service.intent.DefaultIntentRoutingService;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Mode B: a recorded LLM reply drives real deterministic router and canvas-tool code.
 */
public class StubbedEvalReplayTest {

    @Test
    public void shouldReplayRecordedRouterReplyThroughTheRealCanvasToolChain() throws Exception {
        String initialXml = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='Order Service' vertex='1' parent='1'>"
                + "<mxGeometry x='100' y='100' width='140' height='60' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>";
        IntentRoutingResult routing = routeWithRecordedLlmReply(initialXml);
        DrawioCanvasMcpService.DrawioMutationResponse mutation = modifyCanvas(initialXml);
        String finalCanvasXml = new DrawioCanvasXmlToolkit().replaceCells(initialXml, mutation.getCells());

        assertEquals("edit_existing", routing.getRouteType());
        assertTrue(finalCanvasXml.contains("Payment Service"));

        EvalExecution execution = EvalExecution.builder()
                .evalCase(editPaymentServiceCase())
                .trace(EvalTrace.builder()
                        .runStatus(EvalTrace.RunStatus.SUCCESS)
                        .taskOutcome(EvalTrace.TaskOutcome.FULFILLED)
                        .routing(EvalTrace.Routing.builder()
                                .routeType(routing.getRouteType())
                                .diagramType(routing.getDiagramType())
                                .skillName(routing.getSkillName())
                                .build())
                        .toolCalls(List.of(
                                EvalTrace.ToolCall.builder().name(DrawioSkillToolNames.GET_DRAWIO_SKILL)
                                        .status(EvalTrace.RunStatus.SUCCESS).build(),
                                EvalTrace.ToolCall.builder().name(DrawioCanvasToolNames.MODIFY_DIAGRAM)
                                        .status(EvalTrace.RunStatus.SUCCESS).build()))
                        .beforeCanvasHash(Integer.toHexString(initialXml.hashCode()))
                        .afterCanvasHash(Integer.toHexString(finalCanvasXml.hashCode()))
                        .build())
                .finalCanvasXml(finalCanvasXml)
                .gitSha("stubbed-replay")
                .build();

        assertTrue(new DefaultEvalHarness().evaluate(execution).isPassed());
    }

    private IntentRoutingResult routeWithRecordedLlmReply(String canvasXml) throws Exception {
        DefaultIntentRoutingService service = new DefaultIntentRoutingService();
        inject(service, "chatService", new RecordedReplyChatService());
        inject(service, "skillCatalogService", new EmptySkillCatalogService());
        return service.route(IntentRoutingCommand.builder()
                .userId("eval-user")
                .message("新增支付服务并连接订单服务")
                .canvasXml(canvasXml)
                .build());
    }

    private DrawioCanvasMcpService.DrawioMutationResponse modifyCanvas(String initialXml) {
        DrawioCanvasMcpService.ModifyDiagramRequest request = new DrawioCanvasMcpService.ModifyDiagramRequest();
        request.setMode("append");
        request.setXml(initialXml);
        request.setCells("<mxCell id='3' value='Payment Service' vertex='1' parent='1'>"
                + "<mxGeometry x='340' y='100' width='140' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='4' value='charges' edge='1' parent='1' source='2' target='3'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>");
        return new DrawioCanvasMcpService().modifyDiagram(request);
    }

    private EvalCaseDefinition editPaymentServiceCase() {
        EvalCaseDefinition.Expected expected = new EvalCaseDefinition.Expected();
        expected.setRouteType("edit_existing");
        expected.setTaskOutcome(EvalTrace.TaskOutcome.FULFILLED);
        expected.setRequiredToolNamesBeforeMutation(List.of(DrawioSkillToolNames.GET_DRAWIO_SKILL));
        expected.setAllowedMutationTools(List.of(DrawioCanvasToolNames.MODIFY_DIAGRAM));
        expected.setRequireCanvasChange(true);
        expected.setMaxCriticalIssues(0);
        expected.setMaxMajorIssues(0);
        return EvalCaseDefinition.builder()
                .caseId("edit-payment-service-stubbed-001")
                .datasetVersion("core-v1")
                .diagramType("architecture")
                .privacy(new EvalCaseDefinition.Privacy("synthetic", "none"))
                .expected(expected)
                .build();
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class EmptySkillCatalogService extends SkillCatalogService {
        @Override
        public RouterCatalog routerCatalog(String ownerId) {
            return new RouterCatalog("", java.util.Set.of());
        }
    }

    private static class RecordedReplyChatService implements IChatService {

        private static final List<String> REPLY = List.of(
                "{\"routeType\":\"edit_existing\",\"diagramType\":\"architecture\",\"skillName\":\"none\","
                        + "\"needsCanvasQuality\":true,\"needsSemanticReview\":true,"
                        + "\"answerMode\":\"none\",\"answer\":\"\",\"reason\":\"add payment service\"}");

        @Override
        public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
            return List.of();
        }

        @Override
        public String createSession(String agentId, String userId) {
            return "eval-session";
        }

        @Override
        public String ensureSession(String agentId, String userId, String sessionId) {
            return sessionId;
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String message) {
            return REPLY;
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
            return REPLY;
        }

        @Override
        public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
            return Flowable.empty();
        }

        @Override
        public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
            return REPLY;
        }
    }
}
