package org.zipp.ai.ingestion.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.zipp.ai.infrastructure.adapter.repository.JdbcMaterialDeletionDatabasePurger;
import org.zipp.ai.infrastructure.adapter.repository.MySqlDocumentProcessingWorkAdapter;
import org.zipp.ai.infrastructure.adapter.repository.MySqlIndexGenerationCompatibilityAdapter;
import org.zipp.ai.infrastructure.adapter.repository.MySqlIndexProjectionMaintenanceAdapter;
import org.zipp.ai.infrastructure.adapter.repository.MySqlMaterialDeletionWorkAdapter;
import org.zipp.ai.infrastructure.adapter.repository.MySqlMaterializationWorkAdapter;
import org.zipp.ai.infrastructure.adapter.repository.MySqlProcessingQueueAdapter;
import org.zipp.ai.infrastructure.adapter.repository.MySqlSecureUploadWorkAdapter;
import org.zipp.ai.infrastructure.adapter.repository.MySqlVectorProjectionWorkAdapter;

@EnableScheduling
@MapperScan("org.zipp.ai.infrastructure.dao")
@SpringBootApplication(scanBasePackages = "org.zipp.ai.ingestion.worker")
@Import({
        // Keep the Worker isolated from HTTP/turn repositories that belong to the backend app.
        MySqlSecureUploadWorkAdapter.class,
        MySqlProcessingQueueAdapter.class,
        MySqlMaterializationWorkAdapter.class,
        MySqlDocumentProcessingWorkAdapter.class,
        MySqlVectorProjectionWorkAdapter.class,
        MySqlIndexGenerationCompatibilityAdapter.class,
        MySqlIndexProjectionMaintenanceAdapter.class,
        JdbcMaterialDeletionDatabasePurger.class,
        MySqlMaterialDeletionWorkAdapter.class
})
public class IngestionWorkerApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(IngestionWorkerApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
