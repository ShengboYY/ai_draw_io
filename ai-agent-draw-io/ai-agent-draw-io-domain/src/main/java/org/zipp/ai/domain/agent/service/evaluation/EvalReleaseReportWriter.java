package org.zipp.ai.domain.agent.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class EvalReleaseReportWriter {
    public void write(EvalReleaseGateService.Decision decision, Path directory) throws IOException {
        Files.createDirectories(directory);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve("release-gate.json").toFile(), decision);
        Files.writeString(directory.resolve("release-gate.md"), "# Evaluation Release Gate\n\n- outcome: "
                + decision.outcome() + "\n- reasons:\n" + decision.reasons().stream().map(reason -> "  - " + reason)
                .collect(java.util.stream.Collectors.joining("\n")) + "\n");
    }
}
