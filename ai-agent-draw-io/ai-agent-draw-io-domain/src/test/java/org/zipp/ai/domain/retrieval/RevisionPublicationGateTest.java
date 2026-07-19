package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.RevisionGapManifest;
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

    private RevisionPublicationWork work(VectorGenerationProfile profile, VectorProjectionManifest manifest,
                                         StoredArtifact projectionPin, String activeGenerationId) {
        StoredArtifact retrievalPin = new StoredArtifact("retrieval.json.gz", "s3-v2",
                "c".repeat(64), 1024, "application/json+gzip");
        StoredArtifact structurePin = new StoredArtifact("structure.json.gz", "s3-v1",
                "1".repeat(64), 900, "application/json+gzip");
        StoredArtifact evidencePin = new StoredArtifact("evidence.json.gz", "s3-v2",
                "2".repeat(64), 950, "application/json+gzip");
        RevisionProjectionContext context = new RevisionProjectionContext("rev_1", "ver_1", "material_1",
                OwnerType.USER, "user_1", 7, 2, "d".repeat(64), retrievalPin);
        return new RevisionPublicationWork(context, profile, structurePin, evidencePin, null,
                projectionPin, manifest.manifestHash(), 1, 1, 0, 0, 1,
                1, 1, IndexGenerationState.BUILDING, activeGenerationId, manifest.entries());
    }

    private VectorGenerationProfile profile() {
        return new VectorGenerationProfile("drawio-test", "test", "multilingual-e5-large",
                "e".repeat(64), 4, "cosine", "vector-v1", "tokenizer-v1");
    }
}
