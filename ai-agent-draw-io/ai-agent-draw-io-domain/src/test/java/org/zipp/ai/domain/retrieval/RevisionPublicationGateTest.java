package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.retrieval.model.valobj.*;
import org.zipp.ai.domain.retrieval.projection.*;
import org.zipp.ai.domain.retrieval.service.RevisionPublicationGate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevisionPublicationGateTest {

    @Test
    void publishesOnlyAnExactManifestWhoseVectorsAreVisible() {
        VectorGenerationProfile profile = profile();
        VectorProjectionManifest manifest = VectorProjectionManifest.create("rev_1", "ver_1", profile,
                VectorProjectionRole.PRIMARY,
                List.of(new VectorProjectionManifestEntry("chunk_1", "vector_1", "a".repeat(64))));
        StoredArtifact projectionPin = new StoredArtifact("projection.json.gz", "s3-v3",
                "b".repeat(64), 512, "application/json+gzip");
        RevisionPublicationWork work = work(profile, manifest, projectionPin, null);
        RevisionPublicationGate gate = new RevisionPublicationGate();

        gate.verifyManifest(work, manifest);

        assertFalse(gate.allVectorsVisible(work, Set.of()));
        assertTrue(gate.allVectorsVisible(work, Set.of("vector_1")));
    }

    @Test
    void refusesBuildingGenerationWhenAnotherGenerationIsAlreadyActive() {
        VectorGenerationProfile profile = profile();
        VectorProjectionManifest manifest = VectorProjectionManifest.create("rev_1", "ver_1", profile,
                VectorProjectionRole.PRIMARY,
                List.of(new VectorProjectionManifestEntry("chunk_1", "vector_1", "a".repeat(64))));
        RevisionPublicationWork work = work(profile, manifest, new StoredArtifact(
                "projection.json.gz", "s3-v3", "b".repeat(64), 512, "application/json+gzip"), "ig_old");

        assertThrows(IllegalStateException.class,
                () -> new RevisionPublicationGate().verifyManifest(work, manifest));
    }

    @Test
    void partialRevisionRequiresAUserExplainableGapEntry() {
        assertThrows(IllegalArgumentException.class, () -> new RevisionGapManifest(
                "revision-gap-v1", "rev_1", "ver_1", List.of()));

        RevisionGapManifest gaps = new RevisionGapManifest("revision-gap-v1", "rev_1", "ver_1",
                List.of(new RevisionGapManifest.Gap(3,
                        org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality.VISUAL,
                        "VLM_TIMEOUT", true, 0.25)));

        assertTrue(gaps.gaps().get(0).retryable());
    }

    @Test
    void rejectsDurableRevisionWhoseDenseChunksWereRecordedAsLexicalOnly() {
        String structureHash = "1".repeat(64);
        String evidenceHash = "2".repeat(64);
        DocumentStructure structure = new DocumentStructure("document-structure-v1",
                List.of(new DocumentSection("section_1", null, 1, 1, 1, 1, null, structureHash)),
                List.of(), List.of(), structureHash);
        EvidenceUnit evidenceUnit = new EvidenceUnit("evidence_1", "page_1", 1, "section_1",
                EvidenceUnitType.CONTENT, org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality.TEXT,
                "NATIVE", "durable content",
                "3".repeat(64), new StoredArtifact("canonical.json.gz", "s3-v1", "4".repeat(64),
                10, "application/json+gzip"), null,
                List.of(new EvidenceRegion("page_1", 1,
                        new NormalizedBoundingBox(0.1, 0.1, 0.9, 0.9), 0, 15, "block_1")), 1.0);
        EvidenceManifest evidence = new EvidenceManifest("evidence-manifest-v1", "rev_1", "ver_1",
                structureHash, "evidence-builder-v1", evidenceHash, List.of(evidenceUnit), List.of(), List.of());
        RetrievalProjectionManifest retrieval = new RetrievalProjectionManifest("retrieval-projection-v1",
                "rev_1", "ver_1", evidenceHash, "retrieval-builder-v1",
                List.of(new RetrievalChunkProjection("chunk_1", "page_1", "section_1",
                        RetrievalChunkType.CONTENT, org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality.TEXT,
                        "en", true,
                        RetrievalIndexMode.DENSE_AND_LEXICAL, "durable content", "5".repeat(64), null,
                        List.of(), 2, 1.0, 1,
                        List.of(new RetrievalEvidenceMapping("evidence_1", ChunkEvidenceRole.PRIMARY,
                                0, 0, 15)))),
                List.of(new LexicalProjection("chunk_1", "durable content", null, List.of())), "6".repeat(64));

        assertThrows(IllegalArgumentException.class,
                () -> new RevisionPublicationGate().verifySourceChain(
                        sourceChainWork(true, 0), retrieval, evidence, structure));
    }

    private RevisionPublicationWork work(VectorGenerationProfile profile, VectorProjectionManifest manifest,
                                         StoredArtifact projectionPin, String activeGenerationId) {
        StoredArtifact retrievalPin = new StoredArtifact("retrieval.json.gz", "s3-v2",
                "c".repeat(64), 1024, "application/json+gzip");
        StoredArtifact structurePin = new StoredArtifact("structure.json.gz", "s3-v1",
                "1".repeat(64), 900, "application/json+gzip");
        StoredArtifact evidencePin = new StoredArtifact("evidence.json.gz", "s3-v2",
                "2".repeat(64), 950, "application/json+gzip");
        RevisionProjectionContext context = new RevisionProjectionContext("rev_1", "ver_1", "material_1",
                OwnerType.USER, "user_1", 7, 2, "d".repeat(64), retrievalPin, true);
        return new RevisionPublicationWork(context, profile, structurePin, evidencePin, null,
                projectionPin, manifest.manifestHash(), 1, 1, 0, 0, 1,
                1, 1, IndexGenerationState.BUILDING, activeGenerationId, manifest.entries());
    }

    private RevisionPublicationWork sourceChainWork(boolean durableIndexEligible, int expectedProjectionCount) {
        VectorGenerationProfile profile = profile();
        VectorProjectionManifest manifest = VectorProjectionManifest.create("rev_1", "ver_1", profile,
                VectorProjectionRole.PRIMARY, List.of());
        StoredArtifact retrievalPin = new StoredArtifact("retrieval.json.gz", "s3-v2",
                "c".repeat(64), 1024, "application/json+gzip");
        StoredArtifact structurePin = new StoredArtifact("structure.json.gz", "s3-v1",
                "1".repeat(64), 900, "application/json+gzip");
        StoredArtifact evidencePin = new StoredArtifact("evidence.json.gz", "s3-v2",
                "2".repeat(64), 950, "application/json+gzip");
        RevisionProjectionContext context = new RevisionProjectionContext("rev_1", "ver_1", "material_1",
                OwnerType.USER, "user_1", 7, 2, "d".repeat(64), retrievalPin, durableIndexEligible);
        return new RevisionPublicationWork(context, profile, structurePin, evidencePin, null,
                new StoredArtifact("projection.json.gz", "s3-v3", "b".repeat(64), 512,
                        "application/json+gzip"), manifest.manifestHash(), 1, 1, 0, 1, 1,
                expectedProjectionCount, 0, IndexGenerationState.ACTIVE, profile.generationId(), manifest.entries());
    }

    private VectorGenerationProfile profile() {
        return new VectorGenerationProfile("drawio-test", "test", "multilingual-e5-large",
                "e".repeat(64), 4, "cosine", "vector-v1", "tokenizer-v1");
    }
}
