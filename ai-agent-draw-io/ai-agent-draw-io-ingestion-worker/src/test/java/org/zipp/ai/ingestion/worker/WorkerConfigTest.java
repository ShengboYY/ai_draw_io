package org.zipp.ai.ingestion.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerConfigTest {

    @Test
    void disabledMaterializationDoesNotRequirePromotionBeansOrMaterialsBucket() throws Exception {
        assertConditional("originalPromotionPort");
        assertConditional("materializationJobHandler");
        assertDocumentConditional("revisionArtifactPort");
        assertDocumentConditional("documentParserPort");
        assertDocumentConditional("ocrEnginePort");
        assertDocumentConditional("documentProcessingJobHandler");
        assertVectorConditional("indexGenerationCompatibilityCoordinator");
        assertVectorConditional("indexProjectionMaintenanceCoordinator");
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(stream);
            String configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(configuration.contains("materials-bucket: ${MATERIALS_BUCKET:}"));
            assertTrue(configuration.contains("materialization-enabled: ${MATERIAL_MATERIALIZATION_ENABLED:false}"));
            assertTrue(configuration.contains(
                    "document-processing-enabled: ${MATERIAL_DOCUMENT_PROCESSING_ENABLED:false}"));
        }
    }

    private void assertConditional(String methodName) {
        var method = Arrays.stream(WorkerConfig.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, methodName);
        assertTrue(Arrays.asList(condition.name()).contains("worker.materialization-enabled"));
    }

    private void assertDocumentConditional(String methodName) {
        var method = Arrays.stream(WorkerConfig.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, methodName);
        assertTrue(Arrays.asList(condition.name()).contains("worker.document-processing-enabled"));
    }

    private void assertVectorConditional(String methodName) {
        var method = Arrays.stream(WorkerConfig.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, methodName);
        assertTrue(Arrays.asList(condition.name()).contains("worker.vector-projection-enabled"));
    }
}
