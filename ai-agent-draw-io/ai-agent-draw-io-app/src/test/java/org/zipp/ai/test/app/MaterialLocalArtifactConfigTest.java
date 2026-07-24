package org.zipp.ai.test.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.zipp.ai.config.MaterialPreviewConfig;
import org.zipp.ai.config.MaterialRagConfig;
import org.zipp.ai.config.MaterialVisualObservationConfig;
import org.zipp.ai.infrastructure.adapter.filesystem.FileSystemMaterialObjectAdapter;

import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialLocalArtifactConfigTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void onlineMaterialReadersUseLocalArtifactsWithoutCreatingS3Clients() {
        String uploads = temporaryDirectory.resolve("uploads").toString();
        String materials = temporaryDirectory.resolve("materials").toString();

        assertInstanceOf(FileSystemMaterialObjectAdapter.class,
                new MaterialRagConfig().materialRagLocalRevisionArtifactPort(uploads, materials));
        assertInstanceOf(FileSystemMaterialObjectAdapter.class,
                new MaterialPreviewConfig().materialPreviewLocalRevisionArtifactPort(uploads, materials));
        assertInstanceOf(FileSystemMaterialObjectAdapter.class,
                new MaterialVisualObservationConfig()
                        .materialVisualLocalRevisionArtifactPort(uploads, materials));

        assertStorageConditional(MaterialRagConfig.class, "materialRagS3Client", "s3");
        assertStorageConditional(MaterialPreviewConfig.class, "materialPreviewS3Client", "s3");
        assertStorageConditional(MaterialVisualObservationConfig.class, "materialVisualS3Client", "s3");
    }

    private static void assertStorageConditional(Class<?> configuration, String methodName, String storage) {
        var method = Arrays.stream(configuration.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, methodName);
        assertTrue(Arrays.asList(condition.name()).contains("app.material-storage.storage"));
        assertTrue(condition.havingValue().equals(storage));
    }
}
