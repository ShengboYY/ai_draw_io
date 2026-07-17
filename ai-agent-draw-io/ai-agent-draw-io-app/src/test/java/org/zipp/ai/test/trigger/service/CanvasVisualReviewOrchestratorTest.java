package org.zipp.ai.test.trigger.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.CanvasVisualReviewRequestDTO;
import org.zipp.ai.api.dto.CanvasVisualReviewEvidenceDTO;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasCellData;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasSummaryData;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasField;
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
import org.zipp.ai.domain.agent.service.usage.AgentTelemetryMetrics;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
                    assertEquals(List.of("2"), command.getCanvasCells().stream()
                            .map(CanvasCellData::getId).toList());
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
    public void validatesAndForwardsSupplementalVisualEvidence() throws Exception {
        AtomicReference<org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand> captured =
                new AtomicReference<>();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> {
                    captured.set(command);
                    return CanvasVisualReviewResult.builder().available(true).summary("Reviewed all evidence")
                            .issues(List.of()).recommendedHumanReview(false).build();
                });
        CanvasVisualReviewRequestDTO request = request(7L, "sha256:current");
        CanvasVisualReviewEvidenceDTO tile = new CanvasVisualReviewEvidenceDTO();
        tile.setRole("DETAIL_TILE");
        tile.setPageName("Page-1");
        tile.setTileIndex(1);
        tile.setTileCount(4);
        tile.setDataUrl(png());
        request.setAdditionalAfterImages(List.of(tile));
        request.setOriginalUserTask("检查这个图");

        orchestrator.stream("usr_owner", "visual-evidence-run", request, new CapturingEmitter());

        assertEquals(1, captured.get().getAdditionalAfterImages().size());
        assertEquals(Integer.valueOf(16), captured.get().getAdditionalAfterImages().get(0).getWidth());
        assertEquals(Integer.valueOf(1), captured.get().getAdditionalAfterImages().get(0).getTileIndex());
        assertEquals("zh", captured.get().getLanguageHint());

        CanvasVisualReviewRequestDTO englishRequest = request(7L, "sha256:current");
        englishRequest.setAdditionalAfterImages(List.of(tile));
        englishRequest.setOriginalUserTask("Please move the 登录 node to the right.");
        orchestrator.stream("usr_owner", "visual-evidence-language", englishRequest, new CapturingEmitter());
        assertEquals("en", captured.get().getLanguageHint());
    }

    @Test
    public void rejectsMoreSupplementalImagesThanTheReviewBudgetAllows() throws Exception {
        AtomicInteger reviewerCalls = new AtomicInteger();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> {
                    reviewerCalls.incrementAndGet();
                    return CanvasVisualReviewResult.unavailable("unexpected");
                });
        CanvasVisualReviewRequestDTO request = request(7L, "sha256:current");
        List<CanvasVisualReviewEvidenceDTO> images = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            CanvasVisualReviewEvidenceDTO image = new CanvasVisualReviewEvidenceDTO();
            image.setRole("PAGE_OVERVIEW");
            image.setPageId("page-" + index);
            image.setDataUrl(png());
            images.add(image);
        }
        request.setAdditionalAfterImages(images);
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "visual-evidence-overflow", request, emitter);

        assertEquals(0, reviewerCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("too_many_visual_evidence_images"));
        assertTrue(emitter.completed);
    }

    @Test
    public void incompletePageCoverageNeverStartsAutomaticRepair() throws Exception {
        AtomicInteger repairCalls = new AtomicInteger();
        CanvasVisualIssue issue = CanvasVisualIssue.builder()
                .type(CanvasVisualIssueType.LAYOUT_HIERARCHY)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .targetCellIds(List.of("2"))
                .anchorLabels(List.of("API"))
                .region("center")
                .evidence("The visible page is crowded.")
                .repairInstruction("Increase spacing.")
                .repairScope(CanvasVisualRepairScope.LOCAL)
                .build();
        AgentConversationService repairService = new AgentConversationService() {
            @Override
            public void continueDrawing(ChatRequestDTO request,
                                        DrawerContinuationContext continuation,
                                        ResponseBodyEmitter emitter) {
                repairCalls.incrementAndGet();
            }
        };
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(multiPageState(7L, "sha256:current", 6)),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Visible pages need repair")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        seedSourceMutation(telemetryStore, 7L, "sha256:current");
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CanvasVisualReviewRequestDTO request = request(7L, "sha256:current");
        request.setTotalPageCount(6);
        request.setTruncatedPageCount(2);
        request.setAfterImagePageId("page-1");
        request.setAfterImagePageName("Page 1");
        request.setAdditionalAfterImages(List.of(
                pageOverview("page-2", "Page 2"),
                pageOverview("page-3", "Page 3"),
                pageOverview("page-4", "Page 4")));
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "visual-truncated-pages", request, emitter);

        String output = String.join("\n", emitter.sent);
        assertEquals(0, repairCalls.get());
        assertTrue(output, output.contains("\"decision\":\"NEEDS_HUMAN_REVIEW\""));
        assertTrue(output, output.contains("\"type\":\"done\""));
        assertTrue(emitter.completed);
    }

    @Test
    public void rejectsClientPageCountsThatDoNotMatchTheStoredCanvas() throws Exception {
        AtomicInteger reviewerCalls = new AtomicInteger();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(multiPageState(7L, "sha256:current", 6)),
                command -> {
                    reviewerCalls.incrementAndGet();
                    return CanvasVisualReviewResult.builder().available(true).summary("Incomplete approval")
                            .issues(List.of()).recommendedHumanReview(false).build();
                });
        CanvasVisualReviewRequestDTO request = request(7L, "sha256:current");
        request.setTotalPageCount(1);
        request.setTruncatedPageCount(0);
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "visual-page-count-spoof", request, emitter);

        assertEquals(0, reviewerCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("invalid_visual_evidence_coverage"));
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
                assertEquals("source-run", request.getSourceRunId());
                assertEquals("aru_visual_repair", request.getParentRunId());
                assertEquals(Integer.valueOf(1), request.getVisualRepairRound());
                assertEquals(Long.valueOf(7L), request.getExpectedVersion());
                assertEquals("sha256:current", request.getExpectedContentHash());
                assertTrue(request.getCanvasXml().contains("value='API'"));
                assertNull(request.getMaxDeterministicRepairRounds());
                assertNull(request.getMaxReviewIterations());
                assertTrue(request.getMessage().contains("Preserve every unmentioned id"));
                assertEquals("architecture", continuation.diagramType());
                assertEquals(Set.of("2"), continuation.authorization().allowedCellIds());
                assertEquals(Set.of(CanvasField.GEOMETRY, CanvasField.WAYPOINTS),
                        continuation.authorization().allowedFields());
            }
        };
        CanvasVisualIssue issue = CanvasVisualIssue.builder()
                .type(CanvasVisualIssueType.LAYOUT_HIERARCHY)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .targetCellIds(List.of("2"))
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
        seedSourceMutation(telemetryStore, 7L, "sha256:current");
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
    public void edgeFindingThatTargetsOnlyANodeNeverContinuesTheDrawer() throws Exception {
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
                .type(CanvasVisualIssueType.EDGE_TRACEABILITY)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .targetCellIds(List.of("2"))
                .anchorLabels(List.of("API"))
                .region("center")
                .evidence("All connectors appear missing.")
                .repairInstruction("Redraw every connector.")
                .repairScope(CanvasVisualRepairScope.LOCAL)
                .build();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Connectors are missing")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        seedSourceMutation(telemetryStore, 7L, "sha256:current");
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_grounding_conflict",
                request(7L, "sha256:current"), emitter);

        assertEquals(0, repairCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("\"decision\":\"NEEDS_HUMAN_REVIEW\""));
        assertTrue(emitter.completed);
        String metadata = telemetryStore.traceEvents.stream()
                .filter(event -> "visual_review_completed".equals(event.getEventType()))
                .findFirst().orElseThrow().getMetadataJson();
        assertTrue(metadata.contains("\"repairOutcome\":\"grounding_conflict\""));
    }

    @Test
    public void postRepairPolicyCanContinueDrawerForSecondRepair() throws Exception {
        AtomicInteger repairCalls = new AtomicInteger();
        AgentConversationService repairService = new AgentConversationService() {
            @Override
            public void continueDrawing(ChatRequestDTO request,
                                        DrawerContinuationContext continuation,
                                        ResponseBodyEmitter emitter) {
                repairCalls.incrementAndGet();
                assertEquals("source-run", request.getSourceRunId());
                assertEquals("aru_visual_post_repair", request.getParentRunId());
                assertEquals(Integer.valueOf(2), request.getVisualRepairRound());
                assertEquals(Long.valueOf(8L), request.getExpectedVersion());
                assertEquals(Set.of("3"), continuation.authorization().allowedCellIds());
                assertEquals(Set.of(CanvasField.STYLE, CanvasField.WAYPOINTS),
                        continuation.authorization().allowedFields());
            }
        };
        CanvasVisualIssue issue = CanvasVisualIssue.builder()
                .type(CanvasVisualIssueType.EDGE_TRACEABILITY)
                .severity(CanvasVisualIssueSeverity.MAJOR)
                .targetCellIds(List.of("3"))
                .anchorLabels(List.of("API"))
                .region("right")
                .evidence("The repaired edge still crosses the node.")
                .repairInstruction("Route only the anchored edge outside the node.")
                .repairScope(CanvasVisualRepairScope.LOCAL)
                .build();
        CanvasVisualReviewOrchestrator orchestrator = edgeOrchestrator(
                new SequenceCanvasStore(edgeState(8L, "sha256:repair-1")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("One issue remains")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        seedFirstRepair(telemetryStore, "aru_repair_1", 8L, "sha256:repair-1");
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CanvasVisualReviewRequestDTO request = request(8L, "sha256:repair-1");
        request.setStage("POST_REPAIR");
        request.setParentRunId("aru_repair_1");
        request.setVisualRepairRound(1);
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_post_repair", request, emitter);

        assertEquals(1, repairCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("\"decision\":\"REPAIR\""));
        assertFalse(emitter.completed);
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
                .targetCellIds(List.of("2"))
                .anchorLabels(List.of("API"))
                .repairScope(CanvasVisualRepairScope.LOCAL)
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
                .targetCellIds(List.of("2"))
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
        seedVerifiedRepair(telemetryStore, "aru_repair_parent", 8L, "sha256:repaired");
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CanvasVisualReviewRequestDTO request = request(8L, "sha256:repaired");
        request.setStage("VERIFY_ONLY");
        request.setParentRunId("aru_repair_parent");
        request.setVisualRepairRound(2);
        request.setBeforeContentHash("sha256:before-repair");

        orchestrator.stream("usr_owner", "aru_visual_verify", request, new CapturingEmitter());

        String metadata = telemetryStore.traceEvents.stream()
                .filter(event -> "visual_review_completed".equals(event.getEventType()))
                .findFirst().orElseThrow().getMetadataJson();
        assertTrue(metadata.contains("\"autoRepairSucceeded\":true"));
        assertTrue(metadata.contains("\"afterRepairCanvasHash\":\"sha256:repaired\""));
        assertTrue(metadata.contains("\"parentRunId\":\"aru_repair_parent\""));
        assertTrue(metadata.contains("\"visualRepairRound\":2"));
    }

    @Test
    public void verifyOnlyRetriesWhileCrossInstanceRepairSnapshotBecomesVisible() throws Exception {
        AtomicInteger reviewerCalls = new AtomicInteger();
        AtomicInteger visibilityChecks = new AtomicInteger();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(8L, "sha256:repaired")),
                command -> {
                    reviewerCalls.incrementAndGet();
                    return CanvasVisualReviewResult.builder().available(true).summary("Verified")
                            .issues(List.of()).recommendedHumanReview(false).build();
                });
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore() {
            @Override
            public synchronized boolean isVisualRepairResult(String sourceRunId,
                                                             String repairRunId,
                                                             String userId,
                                                             String diagramId,
                                                             Long repairedVersion,
                                                             String repairedCanvasHash,
                                                             int repairRound) {
                return visibilityChecks.incrementAndGet() >= 3 && super.isVisualRepairResult(
                        sourceRunId, repairRunId, userId, diagramId, repairedVersion,
                        repairedCanvasHash, repairRound);
            }
        };
        seedVerifiedRepair(telemetryStore, "aru_repair_parent", 8L, "sha256:repaired");
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CanvasVisualReviewRequestDTO request = request(8L, "sha256:repaired");
        request.setStage("VERIFY_ONLY");
        request.setParentRunId("aru_repair_parent");
        request.setVisualRepairRound(2);
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_delayed_snapshot", request, emitter);

        assertEquals(3, visibilityChecks.get());
        assertEquals(1, reviewerCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("\"decision\":\"APPROVE\""));
        assertTrue(emitter.completed);
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
                .targetCellIds(List.of("2"))
                .anchorLabels(List.of("API"))
                .repairScope(CanvasVisualRepairScope.LOCAL)
                .build();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Still crowded")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        CanvasVisualReviewRequestDTO request = request(7L, "sha256:current");
        request.setStage("VERIFY_ONLY");
        request.setParentRunId("aru_repair_parent");
        request.setVisualRepairRound(2);
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        seedVerifiedRepair(telemetryStore, "aru_repair_parent", 7L, "sha256:current");
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC(),
                        AgentUsageTelemetryService.TelemetryWriteExecutor.direct(),
                        new AgentTelemetryMetrics(registry)));
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
        assertEquals(1D, registry.get("ai.agent.visual.repair.budget.exhausted")
                .tag("round", "2").counter().count(), 0.001D);
    }

    @Test
    public void verifyOnlyRejectsMismatchedRepairParentBeforeCallingReviewer() throws Exception {
        assertVerifyLineageRejected(
                "aru_repair_claimed", "sha256:repaired",
                "aru_repair_other", "sha256:repaired");
    }

    @Test
    public void verifyOnlyRejectsCanvasNotSavedByTheClaimedRepairRun() throws Exception {
        assertVerifyLineageRejected(
                "aru_repair_claimed", "sha256:different",
                "aru_repair_claimed", "sha256:repaired");
    }

    @Test
    public void postMutationStageRejectsACompletedRepairRoundBeforeCallingReviewer() throws Exception {
        AtomicInteger reviewerCalls = new AtomicInteger();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(8L, "sha256:repaired")),
                command -> {
                    reviewerCalls.incrementAndGet();
                    return CanvasVisualReviewResult.unavailable("unexpected");
                });
        CanvasVisualReviewRequestDTO request = request(8L, "sha256:repaired");
        request.setVisualRepairRound(1);
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_mismatch", request, emitter);

        assertEquals(0, reviewerCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("invalid_visual_review_request"));
        assertTrue(emitter.completed);
    }

    @Test
    public void postMutationStageRejectsAParentOutsideTheSourceRun() throws Exception {
        AtomicInteger reviewerCalls = new AtomicInteger();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> {
                    reviewerCalls.incrementAndGet();
                    return CanvasVisualReviewResult.unavailable("unexpected");
                });
        CanvasVisualReviewRequestDTO request = request(7L, "sha256:current");
        request.setParentRunId("other-parent");
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_wrong_parent", request, emitter);

        assertEquals(0, reviewerCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("invalid_visual_review_request"));
        assertTrue(emitter.completed);
    }

    @Test
    public void replayedSourceRunCannotStartASecondAutomaticRepair() throws Exception {
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
                .targetCellIds(List.of("2"))
                .anchorLabels(List.of("API"))
                .repairScope(CanvasVisualRepairScope.LOCAL)
                .build();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Needs spacing")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        seedSourceMutation(telemetryStore, 7L, "sha256:current");
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));

        orchestrator.stream("usr_owner", "aru_visual_first",
                request(7L, "sha256:current"), new CapturingEmitter());
        CapturingEmitter replayEmitter = new CapturingEmitter();
        orchestrator.stream("usr_owner", "aru_visual_replay",
                request(7L, "sha256:current"), replayEmitter);

        assertEquals(1, repairCalls.get());
        assertTrue(String.join("\n", replayEmitter.sent).contains("\"decision\":\"NEEDS_HUMAN_REVIEW\""));
        assertTrue(replayEmitter.completed);
    }

    @Test
    public void sourceRunOwnedByAnotherWorkspaceCannotGrantRepairAuthority() throws Exception {
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
                .targetCellIds(List.of("2"))
                .anchorLabels(List.of("API"))
                .repairScope(CanvasVisualRepairScope.LOCAL)
                .build();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Needs spacing")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry foreignRun = sourceRun();
        foreignRun.setUserId("usr_other");
        telemetryStore.insertRun(foreignRun);
        telemetryStore.insertDiagramSnapshot(snapshot("source-run", 7L, "sha256:current"));
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_foreign",
                request(7L, "sha256:current"), emitter);

        assertEquals(0, repairCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("\"decision\":\"NEEDS_HUMAN_REVIEW\""));
        assertTrue(emitter.completed);
    }

    @Test
    public void sourceRunWithoutTheReviewedCanvasSnapshotCannotGrantRepairAuthority() throws Exception {
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
                .targetCellIds(List.of("2"))
                .anchorLabels(List.of("API"))
                .repairScope(CanvasVisualRepairScope.LOCAL)
                .build();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Needs spacing")
                        .issues(List.of(issue)).recommendedHumanReview(false).build(),
                repairService);
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        telemetryStore.insertRun(sourceRun());
        telemetryStore.insertDiagramSnapshot(snapshot("source-run", 6L, "sha256:older"));
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_wrong_snapshot",
                request(7L, "sha256:current"), emitter);

        assertEquals(0, repairCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("\"decision\":\"NEEDS_HUMAN_REVIEW\""));
        assertTrue(emitter.completed);
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

    @Test
    public void publishesRoundAndDecisionMetricsForProductionReview() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(7L, "sha256:current")),
                command -> CanvasVisualReviewResult.builder().available(true).summary("Looks good")
                        .issues(List.of()).recommendedHumanReview(false).build());
        inject(orchestrator, "agentUsageTelemetryService", new AgentUsageTelemetryService(
                new FakeAgentUsageTelemetryStore(), Clock.systemUTC(),
                AgentUsageTelemetryService.TelemetryWriteExecutor.direct(),
                new AgentTelemetryMetrics(registry)));

        orchestrator.stream("usr_owner", "visual-metric-run",
                request(7L, "sha256:current"), new CapturingEmitter());

        assertEquals(1D, registry.get("ai.agent.visual.review")
                .tag("stage", "post_mutation").tag("round", "0").tag("decision", "approve")
                .counter().count(), 0.001D);
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
                // Keep analyzer cell IDs aligned with the saved canvas used for grounding.
                .cells(List.of(CanvasCellData.builder().id("2").label("API").kind("vertex").build()))
                .summary(CanvasSummaryData.builder().nodeCount(1).edgeCount(0).summary("one node").build())
                .build();
        return new CanvasVisualReviewOrchestrator(store, analyzer, reviewer,
                new CanvasReviewImageValidator(), new AnonymousDemoQuotaService(),
                new VerifiedUserPlatformQuotaService(), repairService);
    }

    private CanvasVisualReviewOrchestrator edgeOrchestrator(
            ICanvasStateStore store,
            org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer reviewer,
            AgentConversationService repairService) {
        ICanvasAnalyzer analyzer = (xml, diagramType) -> CanvasAnalysis.builder()
                .valid(true)
                .severity("ok")
                .issues(List.of())
                .cells(List.of(
                        CanvasCellData.builder().id("2").label("API").kind("node").build(),
                        CanvasCellData.builder().id("3").label("query").kind("edge")
                                .source("2").target("2").build()))
                .summary(CanvasSummaryData.builder().nodeCount(1).edgeCount(1).summary("one node, one edge").build())
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
        request.setParentRunId("source-run");
        request.setVisualRepairRound(0);
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

    private CanvasState edgeState(Long version, String hash) {
        CanvasState state = state(version, hash);
        state.setCurrentXml("<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='2' value='API' vertex='1' parent='1'>"
                + "<mxGeometry x='40' y='40' width='120' height='60' as='geometry'/></mxCell>"
                + "<mxCell id='3' value='query' edge='1' parent='1' source='2' target='2'>"
                + "<mxGeometry relative='1' as='geometry'/></mxCell>"
                + "</root></mxGraphModel>");
        return state;
    }

    private CanvasState multiPageState(Long version, String hash, int pageCount) {
        String pages = java.util.stream.IntStream.rangeClosed(1, pageCount)
                .mapToObj(index -> "<diagram id='page-" + index + "' name='Page " + index + "'/>")
                .collect(java.util.stream.Collectors.joining());
        CanvasState state = state(version, hash);
        state.setCurrentXml("<mxfile>" + pages + "</mxfile>");
        return state;
    }

    private CanvasVisualReviewEvidenceDTO pageOverview(String pageId, String pageName) throws Exception {
        CanvasVisualReviewEvidenceDTO evidence = new CanvasVisualReviewEvidenceDTO();
        evidence.setRole("PAGE_OVERVIEW");
        evidence.setPageId(pageId);
        evidence.setPageName(pageName);
        evidence.setDataUrl(png());
        return evidence;
    }

    private org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry sourceRun() {
        return org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry.builder()
                .id("source-run")
                .diagramId("diagram-1")
                .userId("usr_owner")
                .status("SUCCESS")
                .build();
    }

    private void seedSourceMutation(FakeAgentUsageTelemetryStore store, Long version, String hash) {
        store.insertRun(sourceRun());
        store.insertDiagramSnapshot(snapshot("source-run", version, hash));
    }

    private void seedVerifiedRepair(FakeAgentUsageTelemetryStore store,
                                    String repairRunId,
                                    Long repairedVersion,
                                    String repairedHash) {
        Long sourceVersion = repairedVersion - 2;
        String sourceHash = "sha256:before-" + sourceVersion;
        seedSourceMutation(store, sourceVersion, sourceHash);
        String firstRepairRunId = repairRunId + "_previous";
        assertTrue(store.tryClaimVisualRepair(
                "source-run", "source-run", "usr_owner", "diagram-1", "review-request",
                sourceVersion, sourceHash, 1, firstRepairRunId, Instant.now()));
        org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry firstRepairRun = sourceRun();
        firstRepairRun.setId(firstRepairRunId);
        store.insertRun(firstRepairRun);
        Long intermediateVersion = repairedVersion - 1;
        String intermediateHash = "sha256:before-" + repairedVersion;
        store.insertDiagramSnapshot(snapshot(firstRepairRunId, intermediateVersion, intermediateHash));
        assertTrue(store.tryClaimVisualRepair(
                "source-run", firstRepairRunId, "usr_owner", "diagram-1", "review-request-2",
                intermediateVersion, intermediateHash, 2, repairRunId, Instant.now()));
        org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry repairRun = sourceRun();
        repairRun.setId(repairRunId);
        store.insertRun(repairRun);
        store.insertDiagramSnapshot(snapshot(repairRunId, repairedVersion, repairedHash));
    }

    private void seedFirstRepair(FakeAgentUsageTelemetryStore store,
                                 String repairRunId,
                                 Long repairedVersion,
                                 String repairedHash) {
        Long reviewedVersion = repairedVersion - 1;
        String reviewedHash = "sha256:before-" + repairedVersion;
        seedSourceMutation(store, reviewedVersion, reviewedHash);
        assertTrue(store.tryClaimVisualRepair(
                "source-run", "source-run", "usr_owner", "diagram-1", "review-request",
                reviewedVersion, reviewedHash, 1, repairRunId, Instant.now()));
        org.zipp.ai.domain.agent.model.valobj.usage.AgentRunTelemetry repairRun = sourceRun();
        repairRun.setId(repairRunId);
        store.insertRun(repairRun);
        store.insertDiagramSnapshot(snapshot(repairRunId, repairedVersion, repairedHash));
    }

    private void assertVerifyLineageRejected(String claimedRepairRunId,
                                             String savedHash,
                                             String requestedParentRunId,
                                             String requestedHash) throws Exception {
        AtomicInteger reviewerCalls = new AtomicInteger();
        CanvasVisualReviewOrchestrator orchestrator = orchestrator(
                new SequenceCanvasStore(state(8L, requestedHash)),
                command -> {
                    reviewerCalls.incrementAndGet();
                    return CanvasVisualReviewResult.unavailable("unexpected");
                });
        FakeAgentUsageTelemetryStore telemetryStore = new FakeAgentUsageTelemetryStore();
        seedVerifiedRepair(telemetryStore, claimedRepairRunId, 8L, savedHash);
        inject(orchestrator, "agentUsageTelemetryService",
                new AgentUsageTelemetryService(telemetryStore, Clock.systemUTC()));
        CanvasVisualReviewRequestDTO request = request(8L, requestedHash);
        request.setStage("VERIFY_ONLY");
        request.setParentRunId(requestedParentRunId);
        request.setVisualRepairRound(2);
        CapturingEmitter emitter = new CapturingEmitter();

        orchestrator.stream("usr_owner", "aru_visual_invalid_lineage", request, emitter);

        assertEquals(0, reviewerCalls.get());
        assertTrue(String.join("\n", emitter.sent).contains("invalid_visual_review_request"));
        assertTrue(emitter.completed);
    }

    private org.zipp.ai.domain.agent.model.valobj.usage.AgentDiagramTraceSnapshot snapshot(
            String runId, Long version, String hash) {
        return org.zipp.ai.domain.agent.model.valobj.usage.AgentDiagramTraceSnapshot.builder()
                .id("snapshot-" + runId + "-" + version)
                .runId(runId)
                .diagramId("diagram-1")
                .version(version)
                .canvasHash(hash)
                .createdAt(Instant.now())
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
