package org.zipp.ai.test.trigger.service;

import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.CanvasVisualReviewRequestDTO;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasSummaryData;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssue;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueSeverity;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualIssueType;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.analysis.ICanvasAnalyzer;
import org.zipp.ai.trigger.http.service.CanvasReviewImageValidator;
import org.zipp.ai.trigger.http.service.CanvasVisualReviewOrchestrator;
import org.zipp.ai.trigger.http.service.AgentConversationService;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CanvasVisualReviewOrchestratorTest {

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
    public void repairDecisionContinuesDirectlyIntoOneVisualRepairStream() throws Exception {
        AtomicInteger repairCalls = new AtomicInteger();
        AgentConversationService repairService = new AgentConversationService() {
            @Override
            public void streamVisualRepair(ChatRequestDTO request, String diagramType,
                                           boolean optimizeLayout, ResponseBodyEmitter emitter) {
                repairCalls.incrementAndGet();
                assertEquals("300001", request.getAgentId());
                assertEquals("session-1", request.getSessionId());
                assertEquals(Long.valueOf(7L), request.getExpectedVersion());
                assertEquals(Integer.valueOf(1), request.getMaxReviewIterations());
                assertTrue(request.getMessage().contains("Preserve every unmentioned id"));
                assertEquals("architecture", diagramType);
                assertTrue(optimizeLayout);
            }
        };
        CanvasVisualIssue issue = CanvasVisualIssue.builder()
                .type(CanvasVisualIssueType.LAYOUT_HIERARCHY)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .anchorLabels(List.of("API"))
                .region("center")
                .evidence("Crowded layout")
                .repairInstruction("Increase spacing")
                .build();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Needs spacing")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "visual-run-1", request(7L, "sha256:current"), emitter);

        String output = String.join("\n", emitter.sent);
        assertEquals(1, repairCalls.get());
        assertTrue(output.contains("\"decision\":\"REPAIR\""));
        assertFalse(output.contains("\"type\":\"done\""));
        assertFalse(emitter.completed);
    }

    @Test
    public void verifyOnlyNeverStartsAnotherVisualRepair() throws Exception {
        AtomicInteger repairCalls = new AtomicInteger();
        AgentConversationService repairService = new AgentConversationService() {
            @Override
            public void streamVisualRepair(ChatRequestDTO request, String diagramType,
                                           boolean optimizeLayout, ResponseBodyEmitter emitter) {
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
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "visual-run-2", request, emitter);

        assertEquals(0, repairCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("\"decision\":\"NEEDS_HUMAN_REVIEW\""));
        assertTrue(emitter.completed);
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
                .cells(List.of())
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
                .currentXml("<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/></root></mxGraphModel>")
                .build();
    }

    private String png() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "png", output);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
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
