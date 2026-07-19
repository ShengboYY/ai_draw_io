package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionManifest;
import org.zipp.ai.domain.retrieval.projection.VectorProjectionManifestEntry;
import org.zipp.ai.domain.retrieval.model.valobj.VectorProjectionRole;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class VectorProjectionManifestTest {

    @Test
    void manifestIsDeterministicAndGenerationSpecific() {
        VectorGenerationProfile profile = new VectorGenerationProfile(
                "drawio-retrieval-v1", "prod", "multilingual-e5-large", "c".repeat(64),
                1024, "cosine", "vector-v1", "tokenizer-v1");
        var first = new VectorProjectionManifestEntry("chunk_2", "vector_2", "2".repeat(64));
        var second = new VectorProjectionManifestEntry("chunk_1", "vector_1", "1".repeat(64));

        VectorProjectionManifest manifest = VectorProjectionManifest.create(
                "rev_1", "ver_1", profile, VectorProjectionRole.PRIMARY, List.of(first, second));
        VectorProjectionManifest reordered = VectorProjectionManifest.create(
                "rev_1", "ver_1", profile, VectorProjectionRole.PRIMARY, List.of(second, first));
        VectorGenerationProfile nextProfile = new VectorGenerationProfile(
                "drawio-retrieval-v2", "prod", "multilingual-e5-large", "c".repeat(64),
                1024, "cosine", "vector-v1", "tokenizer-v1");

        assertEquals(List.of("chunk_1", "chunk_2"),
                manifest.entries().stream().map(VectorProjectionManifestEntry::chunkId).toList());
        assertEquals(manifest, reordered);
        assertNotEquals(manifest.manifestHash(), VectorProjectionManifest.create(
                "rev_1", "ver_1", nextProfile, VectorProjectionRole.PRIMARY,
                List.of(first, second)).manifestHash());
    }
}
