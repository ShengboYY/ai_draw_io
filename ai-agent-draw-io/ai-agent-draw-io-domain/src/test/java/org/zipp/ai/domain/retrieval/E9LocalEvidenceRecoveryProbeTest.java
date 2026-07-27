package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.internal.DefaultEvidencePreparationModule;
import org.zipp.ai.domain.retrieval.model.valobj.EmbeddingInputType;
import org.zipp.ai.domain.retrieval.model.valobj.VectorIdPage;
import org.zipp.ai.domain.retrieval.model.valobj.VectorProjection;
import org.zipp.ai.domain.retrieval.port.CandidateRef;
import org.zipp.ai.domain.retrieval.port.AuthorizedCandidate;
import org.zipp.ai.domain.retrieval.port.AuthorizedSource;
import org.zipp.ai.domain.retrieval.port.AuthorizedSourceSet;
import org.zipp.ai.domain.retrieval.port.EvidenceBlobStore;
import org.zipp.ai.domain.retrieval.port.EvidenceCatalog;
import org.zipp.ai.domain.retrieval.port.RetrievalLexicalIndex;
import org.zipp.ai.domain.retrieval.port.RetrievalVectorIndex;
import org.zipp.ai.domain.retrieval.port.SourceResolution;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * E9's repeatable local substitute for unavailable deployed dependencies. The module itself is
 * real; only its catalog, retrieval and blob ports are controlled so each outage can recover.
 */
class E9LocalEvidenceRecoveryProbeTest {
    private static final CatalogOwner OWNER = new CatalogOwner(OwnerType.USER, "e9-probe-owner");

    @Test
    void sourceRevocationBlocksPreparationAndRestorationAllowsNewReadyEvidence() {
        AtomicBoolean revoked = new AtomicBoolean(true);
        EvidenceCatalog catalog = catalog(revoked, new AtomicBoolean(false));
        EvidencePreparationModule module = module(catalog, (candidate, maximumBytes) -> evidenceText());

        PreparationOutcome revokedOutcome = prepare(module, "version-e9-1");
        assertInstanceOf(PreparationOutcome.MaterialNotReady.class, revokedOutcome);

        revoked.set(false);
        PreparationOutcome restoredOutcome = prepare(module, "version-e9-1");
        PreparationOutcome.Ready ready = assertInstanceOf(PreparationOutcome.Ready.class, restoredOutcome);
        assertEquals("version-e9-1", ready.preparedEvidence().bundle().items().get(0).versionId());
    }

    @Test
    void eachPreparedBundleCarriesTheVersionResolvedForThatRequestSnapshot() {
        AtomicReference<String> activeVersion = new AtomicReference<>("version-e9-1");
        AtomicBoolean revoked = new AtomicBoolean(false);
        EvidenceCatalog catalog = catalog(activeVersion, revoked, new AtomicBoolean(false));
        EvidencePreparationModule module = module(catalog, (candidate, maximumBytes) -> evidenceText());

        PreparationOutcome.Ready first = assertInstanceOf(PreparationOutcome.Ready.class,
                prepare(module, "version-e9-1"));
        activeVersion.set("version-e9-2");
        PreparationOutcome.Ready second = assertInstanceOf(PreparationOutcome.Ready.class,
                prepare(module, "version-e9-2"));

        assertEquals("version-e9-1", first.preparedEvidence().bundle().items().get(0).versionId());
        assertEquals("version-e9-2", second.preparedEvidence().bundle().items().get(0).versionId());
    }

    @Test
    void vectorAndBlobOutagesFailClosedThenAllowRecoveryOnANewRun() {
        AtomicBoolean vectorDown = new AtomicBoolean(true);
        AtomicBoolean blobDown = new AtomicBoolean(false);
        EvidenceCatalog catalog = catalog(new AtomicBoolean(false), vectorDown);
        EvidencePreparationModule module = module(catalog, (candidate, maximumBytes) -> {
            if (blobDown.get()) throw new IllegalStateException("synthetic object-store outage");
            return evidenceText();
        });

        PreparationOutcome vectorFailure = prepare(module, "version-e9-1");
        assertInstanceOf(PreparationOutcome.DegradedDependency.class, vectorFailure);

        vectorDown.set(false);
        blobDown.set(true);
        PreparationOutcome blobFailure = prepare(module, "version-e9-1");
        assertInstanceOf(PreparationOutcome.DegradedDependency.class, blobFailure);

        blobDown.set(false);
        PreparationOutcome recovered = prepare(module, "version-e9-1");
        assertInstanceOf(PreparationOutcome.Ready.class, recovered);
    }

    private PreparationOutcome prepare(EvidencePreparationModule module, String versionId) {
        EvidencePreparationCommand command = new EvidencePreparationCommand(
                OWNER, "diagram-e9", "conversation-e9", "request-e9", "run-" + versionId,
                "总结 Agile 流程", CanvasProbe.unavailableProbe(),
                ValidatedSelection.empty(), SourceMode.EXPLICIT_ONLY, List.of(versionId), "REQUIRED", "NONE");
        return module.prepare(command, new RunResourceDomain(), EvidenceProgressListener.NOOP,
                CancellationSignal.NEVER).toCompletableFuture().join();
    }

    private EvidencePreparationModule module(EvidenceCatalog catalog, EvidenceBlobStore blobs) {
        RetrievalLexicalIndex lexical = (queries, sources, route, limit) ->
                List.of(new CandidateRef("chunk-e9-1", "TEXT", 1.0),
                        new CandidateRef("chunk-e9-2", "TEXT", 0.9));
        RetrievalVectorIndex vectors = new RetrievalVectorIndex() {
            @Override public void upsert(List<VectorProjection> projections) { }
            @Override public Set<String> existingVectorIds(List<String> vectorIds) { return Set.of(); }
            @Override public VectorIdPage listVectorIds(String paginationToken, int limit) {
                return new VectorIdPage(List.of(), null);
            }
            @Override public List<String> query(float[] vector, String tenantKey, int topK) {
                return List.of("chunk-e9-1", "chunk-e9-2");
            }
            @Override public void delete(List<String> vectorIds) { }
        };
        return new DefaultEvidencePreparationModule(catalog, lexical,
                Optional.of((texts, inputType) -> List.of(new float[] {1.0f})), Optional.of(vectors),
                (ownerType, ownerKey) -> "e9-tenant", (owner, runId, sources) -> () -> { }, blobs,
                (owner, diagramId) -> Optional.empty(), ForkJoinPool.commonPool(), ForkJoinPool.commonPool(),
                Duration.ofSeconds(2), Duration.ofSeconds(1));
    }

    private EvidenceCatalog catalog(AtomicBoolean revoked, AtomicBoolean vectorDown) {
        return catalog(new AtomicReference<>("version-e9-1"), revoked, vectorDown);
    }

    private EvidenceCatalog catalog(AtomicReference<String> activeVersion, AtomicBoolean revoked,
                                    AtomicBoolean vectorDown) {
        return new EvidenceCatalog() {
            @Override public SourceResolution resolveSources(EvidencePreparationCommand command) {
                String state = revoked.get() ? "ACCESS_DENIED" : "READY";
                return new SourceResolution(SourceMode.EXPLICIT_ONLY,
                        List.of(source(activeVersion.get(), state)), List.of());
            }

            @Override public List<CandidateRef> resolveVectorCandidates(List<String> vectorIds,
                                                                         AuthorizedSourceSet sources) {
                if (vectorDown.get()) throw new IllegalStateException("synthetic Pinecone outage");
                return List.of(new CandidateRef("chunk-e9-1", "TEXT", 1.0),
                        new CandidateRef("chunk-e9-2", "TEXT", 0.9));
            }

            @Override public List<AuthorizedCandidate> reauthorize(List<String> chunkIds,
                                                                     AuthorizedSourceSet sources, int limit) {
                AuthorizedSource source = sources.sources().get(0);
                return List.of(candidate("chunk-e9-1", source.versionId()),
                        candidate("chunk-e9-2", source.versionId()));
            }
        };
    }

    private AuthorizedSource source(String versionId, String state) {
        return new AuthorizedSource("material-e9", versionId, "revision-" + versionId,
                MaterialScopeType.LIBRARY, MaterialScopeType.PERSONAL_LIBRARY_KEY,
                state, false, true, true, false);
    }

    private AuthorizedCandidate candidate(String chunkId, String versionId) {
        StoredArtifact artifact = new StoredArtifact("e9/" + chunkId + ".txt", "object-" + versionId,
                "a".repeat(64), 256, "text/plain");
        return new AuthorizedCandidate(chunkId, "evidence-" + chunkId, "material-e9", versionId,
                "revision-" + versionId, "TEXT", 1, 0.99, artifact, "Agile operating guide");
    }

    private String evidenceText() {
        return "Agile workflow uses iterative planning, short delivery cycles, stakeholder feedback, "
                + "review, adaptation, and recurring retrospectives.";
    }
}
