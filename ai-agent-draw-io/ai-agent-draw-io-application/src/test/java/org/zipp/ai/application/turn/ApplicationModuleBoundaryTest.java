package org.zipp.ai.application.turn;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationModuleBoundaryTest {

    @Test
    void applicationPomDependsOnlyOnInwardModules() throws Exception {
        Path pom = Path.of("pom.xml");
        String xml = Files.readString(pom);

        assertTrue(xml.contains("<artifactId>ai-agent-draw-io-domain</artifactId>"));
        assertTrue(xml.contains("<artifactId>ai-agent-draw-io-types</artifactId>"));
        assertFalse(xml.contains("<artifactId>ai-agent-draw-io-trigger</artifactId>"));
        assertFalse(xml.contains("<artifactId>ai-agent-draw-io-infrastructure</artifactId>"));
        assertFalse(xml.contains("<artifactId>ai-agent-draw-io-app</artifactId>"));
    }
}
