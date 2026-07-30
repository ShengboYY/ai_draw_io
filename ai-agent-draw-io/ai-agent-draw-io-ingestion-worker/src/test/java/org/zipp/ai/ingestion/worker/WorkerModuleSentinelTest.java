package org.zipp.ai.ingestion.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.zipp.ai.infrastructure.adapter.repository.MySqlDocumentProcessingWorkAdapter;
import org.zipp.ai.infrastructure.adapter.repository.MySqlSecureUploadWorkAdapter;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the Worker boundary so backend-only repositories cannot break its application context. */
class WorkerModuleSentinelTest {
    @Test
    void workerScansOnlyItsOwnPackageAndImportsRequiredAdapters() {
        SpringBootApplication application =
                IngestionWorkerApplication.class.getAnnotation(SpringBootApplication.class);
        assertArrayEquals(
                new String[]{"org.zipp.ai.ingestion.worker"},
                application.scanBasePackages());

        Import imported = IngestionWorkerApplication.class.getAnnotation(Import.class);
        assertTrue(Arrays.asList(imported.value()).contains(MySqlSecureUploadWorkAdapter.class));
        assertTrue(Arrays.asList(imported.value()).contains(MySqlDocumentProcessingWorkAdapter.class));
    }
}
