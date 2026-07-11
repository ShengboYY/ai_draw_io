package org.zipp.ai.domain.agent.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalStatisticalReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes machine-readable and reviewer-readable live evaluation summaries. */
public class EvalStatisticalReportWriter {
    private final ObjectMapper mapper = new ObjectMapper();

    public void write(EvalStatisticalReport report, Path directory) throws IOException {
        Files.createDirectories(directory);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("statistical-report.json").toFile(), report);
        String markdown = "# Statistical Eval Report\n\n"
                + "- decision: " + report.getDecision() + "\n"
                + "- TSR@1 estimate: " + report.getTsrAtOne() + "\n"
                + "- 95% cluster bootstrap CI: [" + report.getCiLower() + ", " + report.getCiUpper() + "]\n"
                + "- eligible cases/samples: " + report.getEligibleCases() + "/" + report.getEligibleSamples() + "\n"
                + "- error rate: " + report.getErrorRate() + "\n"
                + "- grader availability: " + report.getGraderAvailability() + "\n"
                + "- tokens in/out: " + report.getInputTokens() + "/" + report.getOutputTokens() + "\n"
                + "- estimated cost: " + report.getEstimatedCost() + "\n"
                + "- total latency ms: " + report.getTotalLatencyMs() + "\n";
        Files.writeString(directory.resolve("statistical-report.md"), markdown);
    }
}
