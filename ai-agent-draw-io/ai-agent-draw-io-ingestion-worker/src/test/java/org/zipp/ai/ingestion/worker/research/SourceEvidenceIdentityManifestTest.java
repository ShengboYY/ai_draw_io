package org.zipp.ai.ingestion.worker.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceEvidenceIdentityManifestTest {

    private static final Path MANIFEST = Path.of("../evaluation/material-rag-research-v1/fixtures/generated/"
            + "source-evidence-identities-v1.json");

    @Test
    void resolvesOnlySourceRegisteredTextOrVisualPageIdentities() throws Exception {
        SourceEvidenceIdentityManifest manifest = SourceEvidenceIdentityManifest.load(MANIFEST, new ObjectMapper());

        assertEquals(List.of("dwh-canonical"), manifest.resolve("drawio-workflow-handbook:v1", 5, "TEXT",
                "The handbook says draw.io XML 是可继续编辑的正式结果.", false));
        assertEquals(List.of("daa-route-compose", "daa-route-scope"), manifest.resolve(
                "drawio-agent-architecture:v1", 3, "VISUAL",
                "SCOPE SOURCES RETRIEVE EVIDENCE BUILD PLAN COMPOSE CANVAS", false));
        assertTrue(manifest.resolve(
                "drawio-workflow-handbook:v1", 5, "TEXT", "unrelated material", false).isEmpty());
        assertTrue(manifest.resolve(
                "drawio-agent-architecture:v1", 3, "TEXT", "route caption", false).isEmpty());
        // A verified source-page artifact may carry the publisher's visual-page identity
        // even when Pinecone returns the page through its text projection.
        assertEquals(List.of("daa-route-compose", "daa-route-scope"), manifest.resolve(
                "drawio-agent-architecture:v1", 3, "TEXT", "route caption", true));
    }
}
