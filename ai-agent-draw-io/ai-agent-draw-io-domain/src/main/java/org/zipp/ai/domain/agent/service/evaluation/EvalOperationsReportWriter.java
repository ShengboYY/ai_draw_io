package org.zipp.ai.domain.agent.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.EvalCaseHealthRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class EvalOperationsReportWriter {
    public void write(EvalCanaryService.Decision canary, List<EvalCaseHealthRecord> health, Path directory) throws IOException {
        Files.createDirectories(directory);
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("canary-decision.json").toFile(), canary);
        List<java.util.Map<String, Object>> rows = health.stream().map(record -> {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("caseId", record.getCaseId()); row.put("caseVersion", record.getCaseVersion());
            row.put("baselineReproduced", record.getBaselineReproduced()); row.put("healthStatus", record.getHealthStatus());
            row.put("summary", record.getSummary()); row.put("updatedAt", record.getUpdatedAt() == null ? null : record.getUpdatedAt().toString());
            return row;
        }).toList();
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("case-health.json").toFile(), rows);
    }
}
