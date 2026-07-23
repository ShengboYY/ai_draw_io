package org.zipp.ai.domain.multimodal;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.canvas.CanvasMutationGate;
import org.zipp.ai.domain.citation.model.valobj.*;
import org.zipp.ai.domain.citation.service.CitationGuard;
import org.zipp.ai.domain.grounding.CanvasCommitModule;
import org.zipp.ai.domain.grounding.EvidenceAccessContext;
import org.zipp.ai.domain.grounding.port.GroundedCanvasCommitPort;
import org.zipp.ai.domain.grounding.port.GroundedRunControlPort;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DirectImageConversionExecutionModuleTest {

    @Test
    void preparesAndAtomicallyCommitsCanvasWithDirectCitations() {
        AtomicReference<GroundedCanvasCommitPort.CommitPlan> committed = new AtomicReference<>();
        TrackingRuns runs = new TrackingRuns();
        DirectImageConversionExecutionModule module = new DefaultDirectImageConversionExecutionModule(
                preparedSource(), commitModule(emptyCanvasStore(), committed), runs);

        DirectImageConversionOutcome outcome = module.execute(command(), EvidenceProgressListener.NOOP,
                CancellationSignal.NEVER).toCompletableFuture().join();

        DirectImageConversionOutcome.Committed result =
                assertInstanceOf(DirectImageConversionOutcome.Committed.class, outcome);
        assertTrue(result.canvasXml().contains("zippCitationSchema=\"1\""));
        assertNotNull(committed.get());
        assertEquals("request-1", committed.get().requestId());
        assertEquals("run-1", committed.get().runId());
        assertEquals(List.of("evidence-image"), committed.get().citations().get(0)
                .evidenceLinks().stream().map(GroundedCanvasCommitPort.EvidenceLink::evidenceId).toList());
        assertEquals("run-1", runs.started.runId());
        assertNull(runs.cancelled);
    }

    @Test
    void confirmationStopsBeforeAtomicCommit() {
        AtomicReference<GroundedCanvasCommitPort.CommitPlan> committed = new AtomicReference<>();
        DirectSourcePreparationModule confirmation =
                (command, resources, progress, cancellation) ->
                        java.util.concurrent.CompletableFuture.completedFuture(
                                new DirectSourceOutcome.NeedsConfirmation(
                                        List.of("UNRESOLVED_EDGE_DIRECTION:e1")));
        DirectImageConversionExecutionModule module = new DefaultDirectImageConversionExecutionModule(
                confirmation, commitModule(emptyCanvasStore(), committed), new TrackingRuns());

        DirectImageConversionOutcome outcome = module.execute(command(), null,
                CancellationSignal.NEVER).toCompletableFuture().join();

        assertEquals(List.of("UNRESOLVED_EDGE_DIRECTION:e1"),
                assertInstanceOf(DirectImageConversionOutcome.NeedsConfirmation.class,
                        outcome).reasons());
        assertNull(committed.get());
    }

    @Test
    void staleCanvasVersionIsRejectedWithoutOverwritingTheStoredCanvas() {
        CanvasState stored = CanvasState.builder().userId("alice").diagramId("diagram-1")
                .diagramType("flowchart").currentXml("<mxGraphModel><root/></mxGraphModel>")
                .contentHash("current-hash").version(2L).build();
        ICanvasStateStore store = new ICanvasStateStore() {
            @Override public Optional<CanvasState> find(String userId, String diagramId) {
                return Optional.of(stored);
            }
            @Override public CanvasState save(CanvasState state) { throw new AssertionError(); }
        };
        AtomicReference<GroundedCanvasCommitPort.CommitPlan> committed = new AtomicReference<>();
        DirectImageConversionExecutionModule module = new DefaultDirectImageConversionExecutionModule(
                preparedSource(), commitModule(store, committed), new TrackingRuns());

        DirectImageConversionOutcome outcome = module.execute(command(), null,
                CancellationSignal.NEVER).toCompletableFuture().join();

        DirectImageConversionOutcome.Rejected rejected =
                assertInstanceOf(DirectImageConversionOutcome.Rejected.class, outcome);
        assertEquals(List.of("VERSION_MISMATCH"), rejected.reasons());
        assertNull(committed.get());
    }

    @Test
    void cancellationAfterPreparationStopsBeforeAtomicCommit() {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<GroundedCanvasCommitPort.CommitPlan> committed = new AtomicReference<>();
        TrackingRuns runs = new TrackingRuns();
        DirectSourcePreparationModule preparation = (command, resources, progress, cancellation) -> {
            DirectSourceOutcome prepared = preparedSource()
                    .prepare(command, resources, progress, cancellation)
                    .toCompletableFuture().join();
            // Model cancellation arriving after source preparation but before the completion handler commits.
            cancelled.set(true);
            return java.util.concurrent.CompletableFuture.completedFuture(prepared);
        };
        DirectImageConversionExecutionModule module = new DefaultDirectImageConversionExecutionModule(
                preparation, commitModule(emptyCanvasStore(), committed), runs);

        DirectImageConversionOutcome outcome = module.execute(command(), null, cancelled::get)
                .toCompletableFuture().join();

        assertInstanceOf(DirectImageConversionOutcome.Cancelled.class, outcome);
        assertNull(committed.get());
        assertEquals("run-1", runs.cancelled.runId());
    }

    private DirectSourcePreparationModule preparedSource() {
        return (command, resources, progress, cancellation) -> {
            String xml = """
                    <mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>
                    <mxCell id="direct-node-start" value="Start" vertex="1" parent="1">
                    <mxGeometry x="120" y="160" width="240" height="80" as="geometry"/></mxCell>
                    </root></mxGraphModel>
                    """;
            EvidenceBundleItem item = new EvidenceBundleItem("direct-node-start", "evidence-image",
                    "material-1", "version-1", "revision-1", "uploaded diagram", 1,
                    "VISUAL", "Start", EvidenceSupportRole.SUPPORT, EvidenceOrigin.EXPLICIT);
            EvidenceAccessContext access = EvidenceAccessContext.from(new EvidenceBundle(
                    "direct-bundle-run-1", "request-1", "run-1", SourceMode.EXPLICIT_ONLY,
                    List.of(item)), false);
            CitationBinding binding = new CitationBinding("direct-node-start",
                    "direct-statement-node-start", StatementKind.NODE_TEXT, "Start",
                    null, null, List.of("direct-node-start"),
                    List.of(new SupportAtom("direct-atom-node-start", "direct-node-start",
                            "Start", SupportAtomRole.DIRECT_QUOTE)), SupportType.EVIDENCE);
            ObservedDiagramGraph graph = new ObservedDiagramGraph(
                    List.of(new ObservedDiagramGraph.Node("start", "Start",
                            ObservedDiagramGraph.Shape.ROUNDED_RECTANGLE,
                            new ObservationBounds(0.1, 0.2, 0.2, 0.1),
                            "", "evidence-image", 0.98)),
                    List.of(), List.of(), List.of());
            resources.markPrepared();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new DirectSourceOutcome.Prepared(graph, xml,
                            List.of("direct-node-start"), access, List.of(binding)));
        };
    }

    private CanvasCommitModule commitModule(ICanvasStateStore store,
                                            AtomicReference<GroundedCanvasCommitPort.CommitPlan> committed) {
        GroundedCanvasCommitPort port = new GroundedCanvasCommitPort() {
            @Override
            public Map<String, InheritedProvenance> findPersistedProvenance(InheritanceQuery query) {
                return Map.of();
            }

            @Override
            public CanvasStateSaveResult commit(CommitPlan plan) {
                committed.set(plan);
                return CanvasStateSaveResult.created(CanvasState.builder()
                        .userId("alice").diagramId("diagram-1").diagramType("flowchart")
                        .currentXml(plan.canvasXml()).contentHash(plan.contentHash()).version(1L).build());
            }
        };
        return new CanvasCommitModule(new CanvasMutationGate(store, new DefaultCanvasAnalyzer()),
                new CitationGuard(requests -> List.of()), port);
    }

    private ICanvasStateStore emptyCanvasStore() {
        return new ICanvasStateStore() {
            @Override
            public Optional<CanvasState> find(String userId, String diagramId) {
                return Optional.empty();
            }

            @Override
            public CanvasState save(CanvasState state) {
                throw new AssertionError("atomic commit port must persist the canvas");
            }
        };
    }

    private DirectImageConversionCommand command() {
        DirectSourceCommand source = new DirectSourceCommand(
                new CatalogOwner(OwnerType.USER, "alice"), "request-1", "run-1",
                new VisualObservationTarget("evidence-image", "material-1", "version-1",
                        "revision-1", 1, "uploaded diagram",
                        new StoredArtifact("visual/source.png", "object-version-1",
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                4, "image/png")),
                "Reconstruct the uploaded diagram");
        return new DirectImageConversionCommand(source, "alice", "diagram-1", "",
                DiagramType.FLOWCHART, null, null);
    }

    private static final class TrackingRuns implements GroundedRunControlPort {
        private RunIdentity started;
        private RunIdentity cancelled;

        @Override public void start(RunIdentity identity) { started = identity; }
        @Override public CancelResult cancel(RunIdentity identity) {
            cancelled = identity;
            return CancelResult.CANCELLED;
        }
    }
}
