package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalGraderResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalHarnessResult;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalTrace;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 1 deterministic harness. It grades a normalized EvalTrace and final XML only.
 */
public class DefaultEvalHarness {

    private static final String ROUTE_TOOL_GRADER_VERSION = "route-tool-v1";
    private static final String XML_GRADER_VERSION = "xml-integrity-v1";
    private static final String VISUAL_GRADER_VERSION = "visual-quality-v1";

    private final DefaultCanvasAnalyzer canvasAnalyzer;

    public DefaultEvalHarness() {
        this(new DefaultCanvasAnalyzer());
    }

    public DefaultEvalHarness(DefaultCanvasAnalyzer canvasAnalyzer) {
        this.canvasAnalyzer = Objects.requireNonNull(canvasAnalyzer, "canvasAnalyzer");
    }

    public EvalHarnessResult evaluate(EvalExecution execution) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(execution.getEvalCase(), "execution.evalCase");
        Objects.requireNonNull(execution.getTrace(), "execution.trace");

        CanvasAnalysis analysis = canvasAnalyzer.analyze(
                execution.getFinalCanvasXml(), execution.getEvalCase().getDiagramType());
        List<EvalGraderResult> graders = List.of(
                gradeRouteAndToolPolicy(execution),
                gradeXmlIntegrity(analysis),
                gradeVisualQuality(execution.getEvalCase().getExpected(), analysis));
        boolean passed = graders.stream().allMatch(EvalGraderResult::isPassed);
        return EvalHarnessResult.builder()
                .caseId(execution.getEvalCase().getCaseId())
                .caseVersion(execution.getEvalCase().getDatasetVersion())
                .gitSha(execution.getGitSha())
                .passed(passed)
                .graders(graders)
                .build();
    }

    private EvalGraderResult gradeRouteAndToolPolicy(EvalExecution execution) {
        EvalCaseDefinition.Expected expected = execution.getEvalCase().getExpected();
        EvalTrace trace = execution.getTrace();
        List<String> evidence = new ArrayList<>();

        String actualRoute = trace.getRouting() == null ? null : trace.getRouting().getRouteType();
        if (notBlank(expected.getRouteType()) && !Objects.equals(expected.getRouteType(), actualRoute)) {
            evidence.add("Expected route " + expected.getRouteType() + " but got " + actualRoute + ".");
        }
        if (expected.getTaskOutcome() != null && expected.getTaskOutcome() != trace.getTaskOutcome()) {
            evidence.add("Expected task outcome " + expected.getTaskOutcome() + " but got " + trace.getTaskOutcome() + ".");
        }

        List<EvalTrace.ToolCall> calls = safeToolCalls(trace);
        int firstMutation = firstMutationIndex(calls);
        Set<String> allowedMutationTools = new HashSet<>(safeList(expected.getAllowedMutationTools()));
        for (EvalTrace.ToolCall call : calls) {
            if (isMutation(call.getName()) && !allowedMutationTools.contains(call.getName())) {
                evidence.add("Mutation tool " + call.getName() + " is not allowed.");
            }
        }
        if (firstMutation >= 0) {
            for (String requiredTool : safeList(expected.getRequiredToolNamesBeforeMutation())) {
                if (!calledBefore(calls, requiredTool, firstMutation)) {
                    evidence.add("Required tool " + requiredTool + " was not called before mutation.");
                }
            }
        }
        if (Boolean.TRUE.equals(expected.getRequireCanvasChange()) && firstMutation < 0) {
            evidence.add("No mutation tool was called for a case that requires a canvas change.");
        }
        if (Boolean.TRUE.equals(expected.getRequireCanvasChange())
                && Objects.equals(trace.getBeforeCanvasHash(), trace.getAfterCanvasHash())) {
            evidence.add("Canvas hash did not change for a case that requires a mutation.");
        }

        return grader("route_tool_policy", ROUTE_TOOL_GRADER_VERSION, evidence);
    }

    private EvalGraderResult gradeXmlIntegrity(CanvasAnalysis analysis) {
        List<String> evidence = new ArrayList<>();
        for (CanvasAnalysisIssue issue : safeIssues(analysis)) {
            if ("critical".equals(issue.getSeverity())) {
                evidence.add("Critical XML issue: " + issue.getType() + ".");
            }
        }
        return grader("xml_integrity", XML_GRADER_VERSION, evidence);
    }

    private EvalGraderResult gradeVisualQuality(EvalCaseDefinition.Expected expected, CanvasAnalysis analysis) {
        int criticalCount = countIssues(analysis, "critical");
        int majorCount = countIssues(analysis, "major");
        List<String> evidence = new ArrayList<>();
        int maxCritical = expected.getMaxCriticalIssues() == null ? 0 : expected.getMaxCriticalIssues();
        int maxMajor = expected.getMaxMajorIssues() == null ? 0 : expected.getMaxMajorIssues();
        if (criticalCount > maxCritical) {
            evidence.add("Critical issue count " + criticalCount + " exceeds " + maxCritical + ".");
        }
        if (majorCount > maxMajor) {
            evidence.add("Major issue count " + majorCount + " exceeds " + maxMajor + ".");
        }
        return grader("visual_quality", VISUAL_GRADER_VERSION, evidence);
    }

    private EvalGraderResult grader(String name, String version, List<String> evidence) {
        return EvalGraderResult.builder()
                .graderName(name)
                .graderVersion(version)
                .passed(evidence.isEmpty())
                .evidence(evidence)
                .build();
    }

    private int firstMutationIndex(List<EvalTrace.ToolCall> calls) {
        for (int index = 0; index < calls.size(); index++) {
            if (isMutation(calls.get(index).getName())) {
                return index;
            }
        }
        return -1;
    }

    private boolean calledBefore(List<EvalTrace.ToolCall> calls, String requiredTool, int beforeIndex) {
        for (int index = 0; index < beforeIndex; index++) {
            if (Objects.equals(requiredTool, calls.get(index).getName())
                    && calls.get(index).getStatus() == EvalTrace.RunStatus.SUCCESS) {
                return true;
            }
        }
        return false;
    }

    private boolean isMutation(String toolName) {
        return DrawioCanvasToolNames.CONSOLIDATED_TOOL_NAMES.contains(toolName);
    }

    private int countIssues(CanvasAnalysis analysis, String severity) {
        return (int) safeIssues(analysis).stream().filter(issue -> severity.equals(issue.getSeverity())).count();
    }

    private List<CanvasAnalysisIssue> safeIssues(CanvasAnalysis analysis) {
        return analysis == null || analysis.getIssues() == null ? List.of() : analysis.getIssues();
    }

    private List<EvalTrace.ToolCall> safeToolCalls(EvalTrace trace) {
        return trace.getToolCalls() == null ? List.of() : trace.getToolCalls();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
