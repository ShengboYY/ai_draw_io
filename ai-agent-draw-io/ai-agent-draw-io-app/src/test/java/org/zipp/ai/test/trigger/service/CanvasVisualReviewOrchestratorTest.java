package org.zipp.ai.test.trigger.service;

import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.CanvasVisualReviewRequestDTO;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasSummaryData;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;
import org.zipp.ai.domain.agent.model.valobj.visualreview.DrawerContinuationContext;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueSeverity;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualRepairScope;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.analysis.ICanvasAnalyzer;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.test.domain.agent.FakeAgentUsageTelemetryStore;
import org.zipp.ai.trigger.http.service.CanvasReviewImageValidator;
import org.zipp.ai.trigger.http.service.CanvasVisualReviewOrchestrator;
import org.zipp.ai.trigger.http.service.AgentConversationService;
import org.zipp.ai.trigger.http.service.VisualReviewRolloutPolicy;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CanvasVisualReviewOrchestratorTest {

    @Test
    public void disabledRolloutSkipsProviderQuotaAndVisibleReview() throws Exception {
        AtomicInteger reviewerCalls = new AtomicInteger();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> {
                    reviewerCalls.incrementAndGet();
                    return CanvasVisualReviewResult.unavailable("unexpected");
                });
        inject(orchestrator, "visualReviewRolloutPolicy", new VisualReviewRolloutPolicy(false, false));
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_disabled", request(7L, "sha256:current"), emitter);

        String output = String.join("\n", emitter.sent);
        assertEquals(0, reviewerCalls.get());
        assertTrue(output.contains("\"type\":\"meta\""));
        assertTrue(output.contains("\"type\":\"done\""));
        assertFalse(output.contains("\"type\":\"review_result\""));
        assertTrue(emitter.completed);
    }

    @Test
    public void streamsStructuredReviewForTheExactCanvasVersion() throws Exception {
        AtomicInteger reviewerCalls = new AtomicInteger();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> {
                    reviewerCalls.incrementAndGet();
                    assertTrue(command.getAfterImageDataUrl().startsWith("data:image/png;base64,"));
                    assertEquals(Long.valueOf(7L), command.getExpectedVersion());
                    return CanvasVisualReviewResult.builder().available(true).summary("Looks good")
                            .issues(List.of()).recommendedHumanReview(false).reviewerVersion("reviewer-v1").build();
                });
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "visual-run-1", request(7L, "sha256:current"), emitter);

        String output = String.join("\n", emitter.sent);
        assertEquals(1, reviewerCalls.get());
        assertTrue(output.contains("\"type\":\"meta\""));
        assertTrue(output.contains("\"type\":\"review_started\""));
        assertTrue(output.contains("\"type\":\"review_result\""));
        assertTrue(output.contains("\"decision\":\"APPROVE\""));
        assertTrue(output.contains("\"sourceRunId\":\"source-run\""));
        assertTrue(output.contains("\"type\":\"done\""));
        assertTrue(emitter.completed);
    }

    @Test
    public void staleCanvasDiscardsTheReviewResult() throws Exception {
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current"), state(8L, "sha256:edited")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Old result")
                        .issues(List.of()).recommendedHumanReview(false).build());
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "visual-run-1", request(7L, "sha256:current"), emitter);

        String output = String.join("\n", emitter.sent);
        assertTrue(output.contains("\"type\":\"review_stale\""));
        assertFalse(output.contains("\"type\":\"review_result\""));
    }

    @Test
    public void initialVersionMismatchNeverCallsTheReviewer() throws Exception {
        AtomicInteger reviewerCalls = new AtomicInteger();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(8L, "sha256:new")),
                command -> {
                    reviewerCalls.incrementAndGet();
                    return CanvasVisualReviewResult.unavailable("unexpected");
                });
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "visual-run-1", request(7L, "sha256:old"), emitter);

        assertEquals(0, reviewerCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("\"type\":\"review_stale\""));
    }

    @Test
    public void repairDecisionContinuesTheOriginalDrawerLoop() throws Exception {
        AtomicInteger repairCalls = new AtomicInteger();
        AgentConversationService repairService = new AgentConversationService() {
            @Override
            public void continueDrawing(ChatRequestDTO request,
                                        DrawerContinuationContext continuation,
                                        ResponseBodyEmitter emitter) {
                repairCalls.incrementAndGet();
                assertEquals("300000", request.getAgentId());
                assertEquals("session-1", request.getSessionId());
                assertEquals(Long.valueOf(7L), request.getExpectedVersion());
                assertEquals("sha256:current", request.getExpectedContentHash());
                assertTrue(request.getCanvasXml().contains("value='API'"));
                assertNull(request.getMaxDeterministicRepairRounds());
                assertNull(request.getMaxReviewIterations());
                assertTrue(request.getMessage().contains("Preserve every unmentioned id"));
                assertEquals("architecture", continuation.diagramType());
                assertTrue(continuation.authorization().allowedCellIds().contains("2"));
            }
        };
        CanvasVisualIssue issue = CanvasVisualIssue.builder()
                .type(CanvasVisualIssueType.LAYOUT_HIERARCHY)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .anchorLabels(List.of("API"))
                .region("center")
                .evidence("Crowded layout")
                .repairInstruction("Increase spacing")
                .repairScope(CanvasVisualRepairScope.LOCAL)
                .build();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Needs spacing")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_repair", request(7L, "sha256:current"), emitter);

        String output = String.join("\n", emitter.sent);
        assertEquals(1, repairCalls.get());
        assertTrue(output.contains("\"decision\":\"REPAIR\""));
        assertFalse(output.contains("\"type\":\"done\""));
        assertFalse(emitter.completed);
        String metadata = telemetryStore.traceEvents.stream()
                .filter(event -> "visual_review_completed".equals(event.getEventType()))
                .findFirst().orElseThrow().getMetadataJson();
        assertTrue(metadata.contains("\"autoRepairAttempted\":true"));
        assertTrue(metadata.contains("aru_repair_"));
    }

    @Test
    public void visibleReviewRolloutDoesNotGrantRepairAuthority() throws Exception {
        AtomicInteger repairCalls = new AtomicInteger();
        AgentConversationService repairService = new AgentConversationService() {
            @Override
            public void continueDrawing(ChatRequestDTO request,
                                        DrawerContinuationContext continuation,
                                        ResponseBodyEmitter emitter) {
                repairCalls.incrementAndGet();
            }
        };
        CanvasVisualIssue issue = CanvasVisualIssue.builder()
                .type(CanvasVisualIssueType.LAYOUT_HIERARCHY)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .build();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Needs spacing")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        inject(orchestrator, "visualReviewRolloutPolicy", new VisualReviewRolloutPolicy(true, false));
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_visible", request(7L, "sha256:current"), emitter);

        String output = String.join("\n", emitter.sent);
        assertEquals(0, repairCalls.get());
        assertTrue(output.contains("\"decision\":\"NEEDS_HUMAN_REVIEW\""));
        assertTrue(emitter.completed);
    }

    @Test
    public void shadowReviewRecordsDecisionWithoutShowingOrRepairingIt() throws Exception {
        AtomicInteger repairCalls = new AtomicInteger();
        AgentConversationService repairService = new AgentConversationService() {
            @Override
            public void continueDrawing(ChatRequestDTO request,
                                        DrawerContinuationContext continuation,
                                        ResponseBodyEmitter emitter) {
                repairCalls.incrementAndGet();
            }
        };
        CanvasVisualIssue issue = CanvasVisualIssue.builder()
                .type(CanvasVisualIssueType.TEXT_READABILITY)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .repairScope(CanvasVisualRepairScope.LOCAL)
                .build();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Needs repair")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CanvasVisualReviewRequestDTO request = request(7L, "sha256:current");
        request.setShadow(true);
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_shadow", request, emitter);

        assertEquals(0, repairCalls.get());
        assertFalse(String.join("\n", emitter.sent).contains("\"type\":\"review_result\""));
        assertTrue(emitter.completed);
        String metadata = telemetryStore.traceEvents.stream()
                .filter(event -> "visual_review_completed".equals(event.getEventType()))
                .findFirst().orElseThrow().getMetadataJson();
        assertTrue(metadata.contains("\"shadow\":true"));
        assertTrue(metadata.contains("\"decision\":\"REPAIR\""));
        assertTrue(metadata.contains("\"autoRepairAttempted\":false"));
    }

    @Test
    public void verifyOnlyTelemetryRecordsSuccessfulRepairHash() throws Exception {
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(8L, "sha256:repaired")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Verified")
                        .issues(List.of()).recommendedHumanReview(false).build());
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CanvasVisualReviewRequestDTO request = request(8L, "sha256:repaired");
        request.setStage("VERIFY_ONLY");
        request.setBeforeContentHash("sha256:before-repair");

        orchestrator.stream("usr_owner", "aru_visual_verify", request, new CapturingEmitter());

        String metadata = telemetryStore.traceEvents.stream()
                .filter(event -> "visual_review_completed".equals(event.getEventType()))
                .findFirst().orElseThrow().getMetadataJson();
        assertTrue(metadata.contains("\"autoRepairSucceeded\":true"));
        assertTrue(metadata.contains("\"afterRepairCanvasHash\":\"sha256:repaired\""));
    }

    @Test
    public void verifyOnlyNeverStartsAnotherVisualRepair() throws Exception {
        AtomicInteger repairCalls = new AtomicInteger();
        AgentConversationService repairService = new AgentConversationService() {
            @Override
            public void continueDrawing(ChatRequestDTO request,
                                        DrawerContinuationContext continuation,
                                        ResponseBodyEmitter emitter) {
                repairCalls.incrementAndGet();
            }
        };
        CanvasVisualIssue issue = CanvasVisualIssue.builder()
                .type(CanvasVisualIssueType.LAYOUT_HIERARCHY)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .build();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Still crowded")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        CanvasVisualReviewRequestDTO request = request(7L, "sha256:current");
        request.setStage("VERIFY_ONLY");
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "visual-run-2", request, emitter);

        assertEquals(0, repairCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("\"decision\":\"NEEDS_HUMAN_REVIEW\""));
        assertTrue(emitter.completed);
        String metadata = telemetryStore.traceEvents.stream()
                .filter(event -> "visual_review_completed".equals(event.getEventType()))
                .findFirst().orElseThrow().getMetadataJson();
        assertTrue(metadata.contains("\"autoRepairSucceeded\":false"));
        assertTrue(metadata.contains("\"afterRepairCanvasHash\":\"sha256:current\""));
    }

    @Test
    public void recordsScalarReviewTelemetryWithoutPersistingVisualContent() throws Exception {
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("sensitive summary")
                        .issues(List.of(CanvasVisualIssue.builder()
                                .type(CanvasVisualIssueType.TEXT_READABILITY)
                                .severity(CanvasVisualIssueSeverity.MAJOR)
                                .evidence("sensitive evidence")
                                .repairInstruction("sensitive instruction")
                                .build()))
                        .recommendedHumanReview(true)
                        .reviewerVersion("visual-agent=300018:model=vlm-1:visual-review-schema-v2")
                        .build());
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CanvasVisualReviewRequestDTO request = request(7L, "sha256:current");
        request.setBeforeContentHash("sha256:before");

        orchestrator.stream("usr_owner", "aru_visual_test", request, new CapturingEmitter());

        assertEquals("SUCCESS", telemetryStore.runs.get(0).getStatus());
        assertTrue(telemetryStore.traceEvents.stream()
                .anyMatch(event -> "visual_review_started".equals(event.getEventType())));
        String completed = telemetryStore.traceEvents.stream()
                .filter(event -> "visual_review_completed".equals(event.getEventType()))
                .findFirst().orElseThrow().getMetadataJson();
        assertTrue(completed.contains("sha256:before"));
        assertTrue(completed.contains("sha256:current"));
        assertTrue(completed.contains("TEXT_READABILITY"));
        assertTrue(completed.contains("MAJOR"));
        assertFalse(completed.contains("sensitive summary"));
        assertFalse(completed.contains("sensitive evidence"));
        assertFalse(completed.contains("sensitive instruction"));
        assertFalse(completed.contains("data:image/png"));
    }

    @Test
    public void recordsUnavailableAndStaleReviewLifecycleEvents() throws Exception {
        FakeAgentUsageTelemetryStore unavailableStore = new FakeAgentUsageTelemetryStore();
        CanvasVisualReviewOrchestrator unavailable = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.unavailable("timeout"));
        inject(unavailable, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(unavailableStore, Clock.systemUTC()));
        unavailable.stream("usr_owner", "aru_visual_unavailable",
                request(7L, "sha256:current"), new CapturingEmitter());

        FakeAgentUsageTelemetryStore staleStore = new FakeAgentUsageTelemetryStore();
        CanvasVisualReviewOrchestrator stale = orchestrator(
                new SequenceCanvasStore(state(8L, "sha256:new")),
                command -> CanvasVisualReviewResult.unavailable("unexpected"));
        inject(stale, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(staleStore, Clock.systemUTC()));
        stale.stream("usr_owner", "aru_visual_stale",
                request(7L, "sha256:current"), new CapturingEmitter());

        assertTrue(unavailableStore.traceEvents.stream()
                .anyMatch(event -> "visual_review_unavailable".equals(event.getEventType())
                        && event.getMetadataJson().contains("timeout")));
        assertTrue(staleStore.traceEvents.stream()
                .anyMatch(event -> "visual_review_stale".equals(event.getEventType())
                        && event.getMetadataJson().contains("before_provider")));
    }

    private CanvasVisualReviewOrchestrator orchestrator(ICanvasStateStore store,
                                                         org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer reviewer) {
        return orchestrator(store, reviewer, new AgentConversationService());
    }

    private CanvasVisualReviewOrchestrator orchestrator(ICanvasStateStore store,
                                                         org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer reviewer,
                                                         AgentConversationService repairService) {
        ICanvasAnalyzer analyzer = (xml, diagramType) -> CanvasAnalysis.builder()
                .valid(true)
                .severity("ok")
                .issues(List.of())
                // Keep the test analysis aligned with the canvas fixture so visual anchors can be authorized.
                .cells(List.of(CanvasCellData.builder().id("2").label("API").kind("vertex").build()))
                .summary(CanvasSummaryData.builder().nodeCount(1).edgeCount(0).summary("one node").build())
                .build();
        return new CanvasVisualReviewOrchestrator(store, analyzer, reviewer,
                new CanvasReviewImageValidator(), new AnonymousDemoQuotaService(),
                new VerifiedUserPlatformQuotaService(), repairService);
    }

    private CanvasVisualReviewRequestDTO request(Long version, String hash) throws Exception {
        CanvasVisualReviewRequestDTO request = new CanvasVisualReviewRequestDTO();
        request.setDiagramId("diagram-1");
        request.setAgentId("300001");
        request.setSessionId("session-1");
        request.setExpectedVersion(version);
        request.setExpectedContentHash(hash);
        request.setSourceRunId("source-run");
        request.setOriginalUserTask("Review this diagram");
        request.setDiagramType("architecture");
        request.setStage("POST_MUTATION");
        request.setAfterImageDataUrl(png());
        request.setRendererVersion("drawio-embed-png-v1");
        return request;
    }

    private CanvasState state(Long version, String hash) {
        return CanvasState.builder()
                .userId("usr_owner")
                .diagramId("diagram-1")
                .diagramType("architecture")
                .version(version)
                .contentHash(hash)
                .currentXml("<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                        + "<mxCell id='2' value='API' vertex='1' parent='1'>"
                        + "<mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>"
                        + "</root></mxGraphModel>")
                .build();
    }

    private String png() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", output);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private void inject(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class SequenceCanvasStore implements ICanvasStateStore {
        private final List<CanvasState> states;
        private int index;

        private SequenceCanvasStore(CanvasState... states) {
            this.states = List.of(states);
        }

        @Override
        public Optional<CanvasState> find(String userId, String diagramId) {
            CanvasState state = states.get(Math.min(index, states.size() - 1));
            index++;
            return Optional.of(state);
        }

        @Override
        public CanvasState save(CanvasState state) {
            return state;
        }
    }

    private static final class CapturingEmitter extends ResponseBodyEmitter {
        private final List<String> sent = new ArrayList<>();
        private boolean completed;

        @Override
        public synchronized void send(Object object) throws IOException {
            sent.add(String.valueOf(object));
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }
    }
}
