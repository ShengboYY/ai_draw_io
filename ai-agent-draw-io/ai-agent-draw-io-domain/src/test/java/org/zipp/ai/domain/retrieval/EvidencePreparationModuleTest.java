package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.internal.DefaultEvidencePreparationModule;
import org.zipp.ai.domain.retrieval.port.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EvidencePreparationModuleTest {
    private final CatalogOwner owner = new CatalogOwner(OwnerType.USER, "alice");

    @Test
    void styleRequestReturnsNotRequiredWithoutTouchingRetrieval() {
        AtomicInteger sourceCalls = new AtomicInteger();
        EvidenceCatalog catalog = catalog(command -> {
            sourceCalls.incrementAndGet();
            return new SourceResolution(SourceMode.NONE, List.of(), List.of());
        });

        PreparationOutcome outcome = module(catalog, List.of()).prepare(command(
                "把颜色改成蓝色", "NONE", SourceMode.NONE), new RunResourceDomain(),
                EvidenceProgressListener.NOOP, CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(PreparationOutcome.NotRequired.class, outcome);
        assertEquals(0, sourceCalls.get());
    }

    @Test
    void conversationPendingAndLibraryPendingHaveDifferentTypedStops() {
        AuthorizedSource conversation = source("PROCESSING", true);
        AuthorizedSource library = source("PROCESSING", false);

        PreparationOutcome waiting = module(catalog(command -> new SourceResolution(
                SourceMode.EXPLICIT_ONLY, List.of(conversation), List.of())), List.of())
                .prepare(command("总结资料", "REQUIRED", SourceMode.EXPLICIT_ONLY),
                        new RunResourceDomain(), EvidenceProgressListener.NOOP, CancellationSignal.NEVER)
                .toCompletableFuture().join();
        PreparationOutcome notReady = module(catalog(command -> new SourceResolution(
                SourceMode.EXPLICIT_ONLY, List.of(library), List.of())), List.of())
                .prepare(command("总结资料", "REQUIRED", SourceMode.EXPLICIT_ONLY),
                        new RunResourceDomain(), EvidenceProgressListener.NOOP, CancellationSignal.NEVER)
                .toCompletableFuture().join();

        assertInstanceOf(PreparationOutcome.Waiting.class, waiting);
        assertInstanceOf(PreparationOutcome.MaterialNotReady.class, notReady);
    }

    @Test
    void pendingConversationUploadStopsBeforeRetrieval() {
        PreparationOutcome outcome = module(catalog(command -> new SourceResolution(
                SourceMode.AUTO, List.of(), List.of(), 1)), List.of())
                .prepare(command("根据刚上传的资料回答", "REQUIRED", SourceMode.AUTO),
                        new RunResourceDomain(), EvidenceProgressListener.NOOP, CancellationSignal.NEVER)
                .toCompletableFuture().join();

        assertInstanceOf(PreparationOutcome.Waiting.class, outcome);
    }

    @Test
    void requestSnapshotControlsReadinessWithoutResolvingRawIdsAgain() {
        AtomicInteger catalogCalls = new AtomicInteger();
        EvidenceCatalog catalog = catalog(command -> {
            catalogCalls.incrementAndGet();
            return new SourceResolution(SourceMode.AUTO, List.of(), List.of());
        });
        EvidencePreparationCommand command = new EvidencePreparationCommand(owner, "diagram-1", "conversation-1",
                "request-1", "run-1", "根据所选附件回答", CanvasProbe.unavailableProbe(),
                ValidatedSelection.empty(), SourceMode.NONE,
                new ResolvedSourceSet(SourceMode.EXPLICIT, List.of(), 1, 0),
                List.of(), "OPTIONAL", "NONE");

        PreparationOutcome outcome = module(catalog, List.of()).prepare(command, new RunResourceDomain(),
                EvidenceProgressListener.NOOP, CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(PreparationOutcome.Waiting.class, outcome);
        assertEquals(0, catalogCalls.get());
    }

    @Test
    void readyOutcomeUsesBatchLeaseAndClosesItWithTheRun() {
        AtomicInteger leaseCloses = new AtomicInteger();
        AuthorizedSource ready = source("READY", false);
        CandidateRef lexicalCandidate = new CandidateRef("chunk-1", "TEXT", 1.0);
        CandidateRef secondLexicalCandidate = new CandidateRef("chunk-2", "TEXT", 0.9);
        StoredArtifact artifact = new StoredArtifact("retrieval/chunk-1.txt", "s3-version-1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 32, "text/plain");
        AuthorizedCandidate authorized = new AuthorizedCandidate("chunk-1", "evidence-1", "material-1",
                "version-1", "revision-1", "TEXT", 1, 0.9, artifact, "Agile Practice Guide");
        AuthorizedCandidate secondAuthorized = new AuthorizedCandidate("chunk-2", "evidence-2", "material-1",
                "version-1", "revision-1", "TEXT", 2, 0.85, artifact, "Agile Practice Guide");
        EvidenceCatalog catalog = new EvidenceCatalog() {
            @Override public SourceResolution resolveSources(EvidencePreparationCommand command) {
                return new SourceResolution(SourceMode.EXPLICIT_ONLY, List.of(ready), List.of());
            }
            @Override public List<CandidateRef> resolveVectorCandidates(List<String> vectorIds,
                                                                         AuthorizedSourceSet sources) {
                return List.of();
            }
            @Override public List<AuthorizedCandidate> reauthorize(List<String> chunkIds,
                                                                   AuthorizedSourceSet sources, int limit) {
                return List.of(authorized, secondAuthorized);
            }
        };
        EvidencePreparationModule module = new DefaultEvidencePreparationModule(catalog,
                (queries, sources, route, limit) -> List.of(lexicalCandidate, secondLexicalCandidate),
                Optional.empty(), Optional.empty(),
                (ownerType, ownerKey) -> "opaque-tenant",
                (owner, runId, sources) -> leaseCloses::incrementAndGet,
                (candidate, maximumBytes) -> candidate.chunkId().equals("chunk-1")
                        ? "Agile uses iterative planning, short delivery cycles, continuous stakeholder "
                                + "feedback, review, adaptation, and recurring retrospectives."
                        : "Agile delivery also includes backlog prioritization, iteration review, risk controls, "
                                + "team roles, feedback loops, and continuous improvement practices.",
                (owner, diagramId) -> Optional.empty(), ForkJoinPool.commonPool());
        RunResourceDomain resources = new RunResourceDomain();

        PreparationOutcome outcome = module.prepare(command("总结 Agile 流程", "REQUIRED",
                        SourceMode.EXPLICIT_ONLY), resources, EvidenceProgressListener.NOOP,
                CancellationSignal.NEVER).toCompletableFuture().join();

        PreparationOutcome.Ready readyOutcome = assertInstanceOf(PreparationOutcome.Ready.class, outcome);
        assertEquals(2, readyOutcome.preparedEvidence().bundle().items().size());
        assertFalse(readyOutcome.preparedEvidence().bundle().items().get(0).text().contains("s3-version-1"));
        resources.closeExactlyOnce(CloseReason.COMPLETED);
        assertEquals(1, leaseCloses.get());
    }

    @Test
    void explicitOnlyRejectsOversizedSelectionBeforeCatalogAccess() {
        AtomicInteger catalogCalls = new AtomicInteger();
        EvidenceCatalog catalog = catalog(command -> {
            catalogCalls.incrementAndGet();
            return new SourceResolution(SourceMode.EXPLICIT_ONLY, List.of(), List.of());
        });
        List<String> versions = java.util.stream.IntStream.rangeClosed(1, 501)
                .mapToObj(index -> "version-" + index).toList();
        EvidencePreparationCommand command = new EvidencePreparationCommand(owner, "diagram-1", "conversation-1",
                "request-1", "run-1", "总结资料", CanvasProbe.unavailableProbe(), ValidatedSelection.empty(),
                SourceMode.EXPLICIT_ONLY, versions, "REQUIRED", "NONE");

        PreparationOutcome outcome = module(catalog, List.of()).prepare(command, new RunResourceDomain(),
                EvidenceProgressListener.NOOP, CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(PreparationOutcome.InsufficientEvidence.class, outcome);
        assertEquals(0, catalogCalls.get());
    }

    @Test
    void explicitSelectionPromotesConflictingNoneModeToStrictEvidence() {
        EvidencePreparationCommand command = new EvidencePreparationCommand(owner, "diagram-1", "conversation-1",
                "request-1", "run-1", "draw from source", CanvasProbe.unavailableProbe(),
                ValidatedSelection.empty(), SourceMode.NONE, List.of("version-1"), "NONE", "NONE");

        assertEquals(SourceMode.EXPLICIT, command.sourceMode());
        assertTrue(command.requiresEvidence());
        assertTrue(command.needsEvidence());
    }

    @Test
    void rejectsStaleSelectionBeforeLoadingSources() {
        AtomicInteger catalogCalls = new AtomicInteger();
        EvidenceCatalog catalog = catalog(command -> {
            catalogCalls.incrementAndGet();
            return new SourceResolution(SourceMode.AUTO, List.of(), List.of());
        });
        EvidencePreparationCommand command = new EvidencePreparationCommand(owner, "diagram-1", "conversation-1",
                "request-1", "run-1", "修改选中节点",
                new CanvasProbe(true, 1, 0, 8L, "new-hash", 0, List.of(), true, false),
                new ValidatedSelection(List.of("cell-1"), 7L, "old-hash"), SourceMode.AUTO,
                List.of(), "OPTIONAL", "REQUIRED");

        PreparationOutcome outcome = module(catalog, List.of()).prepare(command, new RunResourceDomain(),
                EvidenceProgressListener.NOOP, CancellationSignal.NEVER).toCompletableFuture().join();

        assertInstanceOf(PreparationOutcome.StaleCanvasSelection.class, outcome);
        assertEquals(0, catalogCalls.get());
    }

    @Test
    void selectedTargetSeedsItsPersistedCitationBeforeSupplementalSearch() {
        AuthorizedSource ready = source("READY", false);
        StoredArtifact artifact = new StoredArtifact("retrieval/existing.txt", "s3-version-1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 96, "text/plain");
        AuthorizedCandidate authorized = new AuthorizedCandidate("chunk-existing", "evidence-existing",
                "material-1", "version-1", "revision-1", "TEXT", 6, 0.95,
                artifact, "Agile Practice Guide");
        AtomicInteger seedCalls = new AtomicInteger();
        AtomicInteger supplementalCalls = new AtomicInteger();
        EvidenceCatalog catalog = new EvidenceCatalog() {
            @Override public SourceResolution resolveSources(EvidencePreparationCommand command) {
                return new SourceResolution(SourceMode.AUTO, List.of(ready), List.of());
            }
            @Override public List<CandidateRef> resolveVectorCandidates(List<String> vectorIds, AuthorizedSourceSet sources) {
                return List.of();
            }
            @Override public List<AuthorizedCandidate> reauthorize(List<String> chunkIds, AuthorizedSourceSet sources, int limit) {
                assertEquals("chunk-existing", chunkIds.get(0));
                return List.of(authorized);
            }
            @Override public List<CandidateRef> existingTargetCandidates(String diagramId, Long canvasVersion,
                                                                         List<String> cellIds,
                                                                         AuthorizedSourceSet sources, int limit) {
                seedCalls.incrementAndGet();
                assertEquals(List.of("cell-1"), cellIds);
                return List.of(new CandidateRef("chunk-existing", "TEXT", 1.0));
            }
        };
        String xml = "<mxGraphModel><root><mxCell id='0'/><mxCell id='1' parent='0'/>"
                + "<mxCell id='cell-1' value='Product Owner' vertex='1' parent='1'/>"
                + "<mxCell id='cell-2' value='Product Backlog' vertex='1' parent='1'/>"
                + "<mxCell id='edge-1' value='orders' edge='1' source='cell-1' target='cell-2' parent='1'/>"
                + "</root></mxGraphModel>";
        EvidencePreparationModule module = new DefaultEvidencePreparationModule(catalog,
                (queries, sources, route, limit) -> {
                    supplementalCalls.incrementAndGet();
                    return List.of(new CandidateRef("chunk-supplement", "TEXT", 0.9));
                },
                Optional.empty(), Optional.empty(), (ownerType, ownerKey) -> "opaque-tenant",
                (owner, runId, sources) -> () -> { },
                (candidate, maximumBytes) -> "Product Owner maximizes product value and manages priorities.",
                (requestedOwner, diagramId) -> Optional.of(
                        new ServerCanvasPort.ServerCanvasSnapshot(7L, "hash-7", xml)),
                ForkJoinPool.commonPool());
        EvidencePreparationCommand command = new EvidencePreparationCommand(owner, "diagram-1", "conversation-1",
                "request-1", "run-1", "Why does Product Owner maximize value?",
                new CanvasProbe(true, 1, 0, 7L, "hash-7", 1, List.of("NODE"), false, false),
                new ValidatedSelection(List.of("cell-1"), 7L, "hash-7"), SourceMode.AUTO,
                List.of(), "REQUIRED", "REQUIRED");

        PreparationOutcome outcome = module.prepare(command, new RunResourceDomain(),
                EvidenceProgressListener.NOOP, CancellationSignal.NEVER).toCompletableFuture().join();

        PreparationOutcome.Ready readyOutcome = assertInstanceOf(PreparationOutcome.Ready.class, outcome);
        assertEquals(1, seedCalls.get());
        assertEquals(0, supplementalCalls.get());
        assertEquals(EvidenceOrigin.EXISTING_REFERENCE,
                readyOutcome.preparedEvidence().bundle().items().get(0).origin());
        assertEquals("cell-1", readyOutcome.preparedEvidence().targets().get(0).cellId());
        assertEquals("NODE", readyOutcome.preparedEvidence().targets().get(0).kind());
        assertTrue(readyOutcome.preparedEvidence().targets().get(0).nearbyLabels().contains("Product Backlog"));
    }

    private DefaultEvidencePreparationModule module(EvidenceCatalog catalog, List<CandidateRef> candidates) {
        return new DefaultEvidencePreparationModule(catalog,
                (queries, sources, route, limit) -> new ArrayList<>(candidates),
                Optional.empty(), Optional.empty(), (ownerType, ownerKey) -> "opaque-tenant",
                (owner, runId, sources) -> () -> { },
                (candidate, maximumBytes) -> "text", (owner, diagramId) -> Optional.empty(),
                ForkJoinPool.commonPool());
    }

    private EvidenceCatalog catalog(java.util.function.Function<EvidencePreparationCommand, SourceResolution> resolver) {
        return new EvidenceCatalog() {
            @Override public SourceResolution resolveSources(EvidencePreparationCommand command) {
                return resolver.apply(command);
            }
            @Override public List<CandidateRef> resolveVectorCandidates(List<String> vectorIds,
                                                                         AuthorizedSourceSet sources) {
                return List.of();
            }
            @Override public List<AuthorizedCandidate> reauthorize(List<String> chunkIds,
                                                                   AuthorizedSourceSet sources, int limit) {
                return List.of();
            }
        };
    }

    private AuthorizedSource source(String state, boolean conversationScoped) {
        return new AuthorizedSource("material-1", "version-1", "revision-1", MaterialScopeType.LIBRARY,
                MaterialScopeType.PERSONAL_LIBRARY_KEY, state, conversationScoped, true, true, false);
    }

    private EvidencePreparationCommand command(String message, String evidenceNeed, SourceMode mode) {
        return new EvidencePreparationCommand(owner, "diagram-1", "conversation-1", "request-1", "run-1", message,
                new CanvasProbe(false, 0, 0, null, "", 0, List.of(), false, false),
                new ValidatedSelection(List.of(), null, ""), mode,
                mode == SourceMode.NONE ? List.of() : List.of("version-1"),
                evidenceNeed, "NONE");
    }
}
