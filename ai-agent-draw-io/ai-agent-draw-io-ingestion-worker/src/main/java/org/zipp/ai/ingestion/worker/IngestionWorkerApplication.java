package org.zipp.ai.ingestion.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan("org.zipp.ai.infrastructure.dao")
@SpringBootApplication(scanBasePackages = {
        "org.zipp.ai.ingestion.worker",
        "org.zipp.ai.infrastructure.adapter.repository"
})
public class IngestionWorkerApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(IngestionWorkerApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
