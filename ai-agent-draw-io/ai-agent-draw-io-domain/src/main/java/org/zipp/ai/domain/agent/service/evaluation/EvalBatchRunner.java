package org.zipp.ai.domain.agent.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Runs every synthetic disk case through a supplied Mode A/B replay and emits JSON/Markdown evidence. */
public class EvalBatchRunner {
    private final EvalCaseLoader loader = new EvalCaseLoader();
    private final DefaultEvalHarness harness = new DefaultEvalHarness();

    public List<EvalHarnessResult> run(Path casesDir, ExecutionFactory factory, Path reportDir) throws IOException {
        List<EvalHarnessResult> results;
        try (var paths = Files.walk(casesDir)) {
            results = paths.filter(path -> path.toString().endsWith(".yaml"))
                    .sorted()
                    .map(path -> execute(path, factory))
                    .toList();
        }
        Files.createDirectories(reportDir);
        new ObjectMapper().writeValue(reportDir.resolve("eval-report.json").toFile(), results);
        Files.writeString(reportDir.resolve("eval-report.md"), markdown(results));
        return results;
    }

    private EvalHarnessResult execute(Path path, ExecutionFactory factory) {
        long startedAt = System.nanoTime();
        EvalCaseDefinition evalCase = null;
        try (var input = Files.newInputStream(path)) {
            evalCase = loader.load(input);
            return harness.evaluate(factory.create(evalCase));
        } catch (Exception e) {
            return EvalHarnessResult.builder()
                    .caseId(evalCase == null ? fileCaseId(path) : evalCase.getCaseId())
                    .caseVersion(evalCase == null ? null : evalCase.getDatasetVersion())
                    .latencyMs((System.nanoTime() - startedAt) / 1_000_000)
                    .status(EvalHarnessResult.Status.ERROR)
                    .errorClass(e.getClass().getSimpleName())
                    .errorMessage(e.getMessage())
                    .passed(false)
                    .build();
        }
    }

    private String fileCaseId(Path path) {
        String name = path.getFileName().toString();
        int extension = name.lastIndexOf('.');
        return extension > 0 ? name.substring(0, extension) : name;
    }

    private String markdown(List<EvalHarnessResult> results) {
        StringBuilder report = new StringBuilder("# Eval report\n\n");
        for (EvalHarnessResult result : results) {
            report.append("## ").append(result.getCaseId()).append(" — ").append(result.getStatus()).append("\n\n")
                    .append("- case version: ").append(result.getCaseVersion()).append("\n")
                    .append("- git SHA: ").append(result.getGitSha()).append("\n")
                    .append("- latency: ").append(result.getLatencyMs()).append(" ms\n");
            if (result.getErrorClass() != null) {
                report.append("- error: ").append(result.getErrorClass()).append(": ")
                        .append(result.getErrorMessage()).append("\n");
            }
            for (var grader : result.getGraders()) {
                report.append("- grader ").append(grader.getGraderName()).append("@")
                        .append(grader.getGraderVersion()).append(": ")
                        .append(grader.isPassed() ? "PASS" : "FAIL");
                if (!grader.getEvidence().isEmpty()) {
                    report.append(" — ").append(String.join("; ", grader.getEvidence()));
                }
                report.append("\n");
            }
            report.append("\n");
        }
        return report.toString();
    }

    @FunctionalInterface
    public interface ExecutionFactory {
        EvalExecution create(EvalCaseDefinition evalCase) throws Exception;
    }
}
