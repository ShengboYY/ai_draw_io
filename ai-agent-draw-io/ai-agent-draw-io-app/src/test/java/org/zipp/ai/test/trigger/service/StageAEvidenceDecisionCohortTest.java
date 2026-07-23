package org.zipp.ai.test.trigger.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.Test;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.IIntentRoutingService;
import org.zipp.ai.domain.agent.service.canvas.DefaultDrawioCanvasSnapshotService;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.domain.retrieval.EvidencePreparationModule;
import org.zipp.ai.domain.retrieval.PreparationOutcome;
import org.zipp.ai.trigger.http.service.AgentConversationService;
import org.zipp.ai.trigger.http.service.DrawioPromptContextBuilder;
import org.zipp.ai.trigger.http.service.DrawioStreamResponseWriter;
import org.zipp.ai.trigger.http.service.DrawioToolCallRenderer;
import org.zipp.ai.trigger.http.service.SkillContentProvider;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * Executes the frozen Stage A cohort at the HTTP-to-evidence boundary without a model, vector store,
 * or canvas writer. Retrieval internals and actual grounded commits are covered by later stages.
 */
public class StageAEvidenceDecisionCohortTest {

    @Test
    public void frozenCohortRendersEveryOutcomeAndBlocksAllUnsafeCases() throws Exception {
        JSONArray cases = frozenCohort().getJSONArray("cases");
        int ready = 0;
        int insufficient = 0;
        int clarification = 0;
        int degraded = 0;
        int notRequired = 0;

        for (int index = 0; index < cases.size(); index++) {
            JSONObject cohortCase = cases.getJSONObject(index);
            String expectedOutcome = cohortCase.getString("expectedOutcome");
            AtomicInteger preparationCalls = new AtomicInteger();
            CountingChatService drawer = new CountingChatService();
            AgentConversationService service = serviceFor(expectedOutcome, preparationCalls, drawer);

            ChatRequestDTO request = requestFor(cohortCase);
            String responseType = service.chat(request).getType();

            assertEquals("case=" + cohortCase.getString("caseId"), expectedResponseType(expectedOutcome), responseType);
            assertEquals("case=" + cohortCase.getString("caseId"), 1, preparationCalls.get());

            switch (expectedOutcome) {
                case "Ready" -> {
                    ready++;
                    // Grounded draw persistence is intentionally unavailable until E7/E8.
                    assertEquals("case=" + cohortCase.getString("caseId"), 0, drawer.handleMessageCalls);
                }
                case "InsufficientEvidence" -> {
                    insufficient++;
                    assertBlockedBeforeDrawer(cohortCase, drawer);
                }
                case "ClarificationNeeded" -> {
                    clarification++;
                    assertBlockedBeforeDrawer(cohortCase, drawer);
                }
                case "DegradedDependency" -> {
                    degraded++;
                    assertBlockedBeforeDrawer(cohortCase, drawer);
                }
                case "NotRequired" -> {
                    notRequired++;
                    // This outcome performs no material retrieval and may continue as ordinary drawing.
                    assertEquals("case=" + cohortCase.getString("caseId"), 1, drawer.handleMessageCalls);
                }
                default -> throw new AssertionError("unknown outcome " + expectedOutcome);
            }
        }

        assertEquals(12, ready);
        assertEquals(6, insufficient);
        assertEquals(4, clarification);
        assertEquals(4, degraded);
        assertEquals(4, notRequired);
    }

    private void assertBlockedBeforeDrawer(JSONObject cohortCase, CountingChatService drawer) {
        assertEquals("case=" + cohortCase.getString("caseId"), "blocked",
                cohortCase.getString("expectedCanvasMutation"));
        assertEquals("case=" + cohortCase.getString("caseId"), 0, drawer.handleMessageCalls);
        assertEquals("case=" + cohortCase.getString("caseId"), 0, drawer.handleMessageStreamCalls);
    }

    private AgentConversationService serviceFor(String expectedOutcome,
                                                AtomicInteger preparationCalls,
                                                CountingChatService drawer) throws Exception {
        AgentConversationService service = new AgentConversationService();
        inject(service, "promptContextBuilder", new DrawioPromptContextBuilder(new DefaultDrawioCanvasSnapshotService()));
        inject(service, "skillContentProvider", new EmptySkillContentProvider());
        inject(service, "streamResponseWriter", new DrawioStreamResponseWriter(new DrawioToolCallRenderer()));
        inject(service, "anonymousDemoQuotaService", new AnonymousDemoQuotaService());
        inject(service, "verifiedUserPlatformQuotaService", new VerifiedUserPlatformQuotaService());
        inject(service, "materialRagEnabled", true);
        inject(service, "chatService", drawer);
        inject(service, "intentRoutingService", new FactualDrawingRoutingService());
        inject(service, "evidencePreparationModule", (EvidencePreparationModule) (command, resources, progress, cancellation) -> {
            preparationCalls.incrementAndGet();
            return CompletableFuture.completedFuture(outcomeFor(expectedOutcome));
        });
        return service;
    }

    private PreparationOutcome outcomeFor(String expectedOutcome) {
        return switch (expectedOutcome) {
            case "Ready" -> new PreparationOutcome.Ready(null, null);
            case "InsufficientEvidence" -> new PreparationOutcome.InsufficientEvidence(List.of("SOURCE_SUPPORT_INCOMPLETE"));
            case "ClarificationNeeded" -> new PreparationOutcome.ClarificationNeeded("AMBIGUOUS_CLAIM", List.of());
            case "DegradedDependency" -> new PreparationOutcome.DegradedDependency(List.of("DEPENDENCY_UNAVAILABLE"));
            case "NotRequired" -> new PreparationOutcome.NotRequired();
            default -> throw new IllegalArgumentException("unknown outcome " + expectedOutcome);
        };
    }

    private String expectedResponseType(String expectedOutcome) {
        return switch (expectedOutcome) {
            case "Ready" -> "capability_unavailable";
            case "InsufficientEvidence" -> "insufficient_evidence";
            case "ClarificationNeeded" -> "claim_clarification";
            case "DegradedDependency" -> "retrieval_degraded";
            case "NotRequired" -> "user";
            default -> throw new IllegalArgumentException("unknown outcome " + expectedOutcome);
        };
    }

    private ChatRequestDTO requestFor(JSONObject cohortCase) {
        ChatRequestDTO request = new ChatRequestDTO();
        request.setAgentId("300000");
        request.setUserId("anon_123e4567-e89b-42d3-a456-426614174000");
        request.setSessionId("stage-a-" + cohortCase.getString("caseId"));
        request.setDiagramId("diagram-" + cohortCase.getString("caseId"));
        request.setMessage(cohortCase.getString("request"));
        request.setSourceMode("AUTO");
        return request;
    }

    private JSONObject frozenCohort() throws Exception {
        Path directory = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && directory != null; depth++, directory = directory.getParent()) {
            Path cohort = directory.resolve("evaluation/material-rag-research-v1/fixtures/stage-a-evidence-decision-cohort-v1.json");
            if (Files.isRegularFile(cohort)) {
                return JSON.parseObject(Files.readString(cohort));
            }
        }
        throw new IllegalStateException("frozen Stage A cohort fixture was not found from the Maven working directory");
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static IntentRoutingResult factualDrawingRoute() {
        IntentRoutingResult result = new IntentRoutingResult();
        result.setRouteType("create_new");
        result.setDiagramType("architecture");
        result.setSkillName("none");
        result.setAnswer("");
        result.setReason("Stage A frozen cohort");
        result.setEvidenceNeed("OPTIONAL");
        result.setTargetNeed("NONE");
        result.setSourceUse("RETRIEVAL");
        return result;
    }

    private static class FactualDrawingRoutingService implements IIntentRoutingService {
        @Override
        public IntentRoutingResult route(IntentRoutingCommand command) {
            return factualDrawingRoute();
        }
    }

    private static class EmptySkillContentProvider extends SkillContentProvider {
        @Override
        public String buildSkillSection(List<String> skillNames, String ownerId) {
            return "";
        }

        @Override
        public SkillSection buildSkillSectionWithMetadata(List<String> skillNames, String ownerId) {
            return SkillSection.empty();
        }
    }

    private static class CountingChatService implements IChatService {
        private int handleMessageCalls;
        private int handleMessageStreamCalls;

        @Override
        public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
            return List.of();
        }

        @Override
        public String createSession(String agentId, String userId) {
            return "stage-a-session";
        }

        @Override
        public String ensureSession(String agentId, String userId, String sessionId) {
            return sessionId;
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String message) {
            return handleMessage(agentId, userId, "stage-a-session", message);
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
            handleMessageCalls++;
            return List.of("{\"type\":\"user\",\"content\":\"ok\"}");
        }

        @Override
        public List<String> handleMessage(String agentId, String userId, String sessionId, String message,
                                          AgentUsageTelemetryContext.RunContext runContext) {
            return handleMessage(agentId, userId, sessionId, message);
        }

        @Override
        public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
            handleMessageStreamCalls++;
            return Flowable.empty();
        }

        @Override
        public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message,
                                                   AgentUsageTelemetryContext.RunContext runContext) {
            return handleMessageStream(agentId, userId, sessionId, message);
        }

        @Override
        public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
            return List.of("{\"type\":\"user\",\"content\":\"ok\"}");
        }
    }
}
