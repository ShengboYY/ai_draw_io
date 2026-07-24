package org.zipp.ai.ingestion.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.zipp.ai.domain.ingestion.port.OriginalPromotionPort;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import org.zipp.ai.domain.material.port.MaterialDeletionObjectPort;
import org.zipp.ai.infrastructure.adapter.filesystem.FileSystemMaterialObjectAdapter;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerConfigTest {

    @TempDir
    Path temporaryDirectory;

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
            assertTrue(configuration.contains("import: optional:file:.env[.properties]"));
            assertTrue(configuration.contains("materials-bucket: ${MATERIALS_BUCKET:}"));
            assertTrue(configuration.contains("materialization-enabled: ${MATERIAL_MATERIALIZATION_ENABLED:false}"));
            assertTrue(configuration.contains(
                    "document-processing-enabled: ${MATERIAL_DOCUMENT_PROCESSING_ENABLED:false}"));
            assertTrue(configuration.contains("map-underscore-to-camel-case: true"));
        }
    }

    @Test
    void localStorageDoesNotCreateAwsQuarantineBeans() throws Exception {
        assertStorageConditional("workerS3Client", "s3");
        assertStorageConditional("pinnedQuarantineContentPort", "s3");
        assertStorageConditional("localPinnedQuarantineContentPort", "local");
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(stream);
            String configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(configuration.contains("storage: ${MATERIAL_UPLOAD_STORAGE:local}"));
            assertTrue(configuration.contains(
                    "local-upload-root: ${MATERIAL_UPLOAD_LOCAL_ROOT:./data/material-uploads}"));
            assertTrue(configuration.contains(
                    "local-materials-root: ${MATERIAL_LOCAL_ROOT:./data/material-objects}"));
        }
    }

    @Test
    void localStorageProvidesPromotionArtifactAndDeletionPorts() {
        FileSystemMaterialObjectAdapter adapter = new WorkerConfig().localMaterialObjectAdapter(
                temporaryDirectory.resolve("uploads").toString(),
                temporaryDirectory.resolve("materials").toString());

        assertInstanceOf(OriginalPromotionPort.class, adapter);
        assertInstanceOf(RevisionArtifactPort.class, adapter);
        assertInstanceOf(MaterialDeletionObjectPort.class, adapter);
        assertStorageConditional("localMaterialObjectAdapter", "local");
        assertStorageExpression("originalPromotionPort", "s3");
        assertStorageExpression("revisionArtifactPort", "s3");
        assertStorageExpression("materialDeletionObjectPort", "s3");
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

    private void assertStorageConditional(String methodName, String storage) {
        var method = Arrays.stream(WorkerConfig.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, methodName);
        assertTrue(Arrays.asList(condition.name()).contains("worker.storage"));
        assertTrue(condition.havingValue().equals(storage));
    }

    private void assertStorageExpression(String methodName, String storage) {
        var method = Arrays.stream(WorkerConfig.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        ConditionalOnExpression condition = method.getAnnotation(ConditionalOnExpression.class);
        assertNotNull(condition, methodName);
        assertTrue(condition.value().contains("worker.storage:local"));
        assertTrue(condition.value().contains("'" + storage + "'"));
    }
}
