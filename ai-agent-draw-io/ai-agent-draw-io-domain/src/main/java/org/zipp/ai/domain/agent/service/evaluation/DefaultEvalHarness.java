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
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

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
        long startedAt = System.nanoTime();
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(execution.getEvalCase(), "execution.evalCase");
        Objects.requireNonNull(execution.getTrace(), "execution.trace");

        CanvasAnalysis analysis = canvasAnalyzer.analyze(
                execution.getFinalCanvasXml(), execution.getEvalCase().getDiagramType());
        List<EvalGraderResult> graders = List.of(
                gradeRouteAndToolPolicy(execution),
                gradeXmlIntegrity(execution.getFinalCanvasXml()),
                gradeVisualQuality(execution.getEvalCase().getExpected(), analysis));
        boolean passed = graders.stream().allMatch(EvalGraderResult::isPassed);
        return EvalHarnessResult.builder()
                .caseId(execution.getEvalCase().getCaseId())
                .caseVersion(execution.getEvalCase().getCaseVersion())
                .gitSha(execution.getGitSha())
                .executionProfileHash(execution.getExecutionProfileHash())
                .promptConfigHash(execution.getPromptConfigHash())
                .skillCatalogHash(execution.getSkillCatalogHash())
                .toolPolicyVersion(execution.getToolPolicyVersion())
                .latencyMs((System.nanoTime() - startedAt) / 1_000_000)
                .status(passed ? EvalHarnessResult.Status.PASS : EvalHarnessResult.Status.FAIL)
                .passed(passed)
                .graders(graders)
                .build();
    }

    private EvalGraderResult gradeRouteAndToolPolicy(EvalExecution execution) {
        EvalCaseDefinition.Expected expected = execution.getEvalCase().getExpected();
        EvalTrace trace = execution.getTrace();
        List<String> evidence = new ArrayList<>();

        EvalTrace.Routing routing = trace.getRouting();
        String actualRoute = routing == null ? null : routing.getRouteType();
        if (notBlank(expected.getRouteType()) && !Objects.equals(expected.getRouteType(), actualRoute)) {
            evidence.add("Expected route " + expected.getRouteType() + " but got " + actualRoute + ".");
        }
        compare("route diagram type", expected.getRouteDiagramType(),
                routing == null ? null : routing.getDiagramType(), evidence);
        compare("skill", expected.getSkillName(), routing == null ? null : routing.getSkillName(), evidence);
        compare("needsCanvasQuality", expected.getNeedsCanvasQuality(),
                routing == null ? null : routing.getNeedsCanvasQuality(), evidence);
        compare("needsSemanticReview", expected.getNeedsSemanticReview(),
                routing == null ? null : routing.getNeedsSemanticReview(), evidence);
        compare("answer mode", expected.getAnswerMode(),
                routing == null ? null : routing.getAnswerMode(), evidence);
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

    private EvalGraderResult gradeXmlIntegrity(String xml) {
        List<String> evidence = new ArrayList<>();
        try {
            Document document = DocumentHelper.parseText(xml);
            if (!"mxGraphModel".equals(document.getRootElement().getName())) {
                evidence.add("Root element must be mxGraphModel.");
                return grader("xml_integrity", XML_GRADER_VERSION, evidence);
            }
            Element root = document.getRootElement().element("root");
            if (root == null) {
                evidence.add("mxGraphModel is missing a root element.");
            } else {
                Set<String> ids = new HashSet<>();
                List<Element> cells = root.elements("mxCell");
                for (Element cell : cells) {
                    String id = cell.attributeValue("id");
                    if (id == null || id.isBlank() || !ids.add(id)) {
                        evidence.add("Cell id is missing or duplicated: " + id + ".");
                    }
                    boolean vertex = "1".equals(cell.attributeValue("vertex"));
                    boolean edge = "1".equals(cell.attributeValue("edge"));
                    if (vertex && edge) {
                        evidence.add("Cell cannot be both vertex and edge: " + id + ".");
                    }
                    if ((vertex || edge) && cell.element("mxGeometry") == null) {
                        evidence.add("Drawable cell is missing mxGeometry: " + id + ".");
                    }
                }
                if (!ids.contains("0") || !ids.contains("1")) {
                    evidence.add("Canvas must contain base cells 0 and 1.");
                }
                for (Element cell : cells) {
                    validateReference("parent", cell.attributeValue("parent"), cell.attributeValue("id"), ids, evidence);
                    if ("1".equals(cell.attributeValue("edge"))) {
                        validateRequiredReference("source", cell.attributeValue("source"), cell.attributeValue("id"), ids, evidence);
                        validateRequiredReference("target", cell.attributeValue("target"), cell.attributeValue("id"), ids, evidence);
                    }
                }
                DocumentHelper.parseText(document.asXML());
            }
        } catch (Exception e) {
            evidence.add("XML is not parseable.");
        }
        return grader("xml_integrity", XML_GRADER_VERSION, evidence);
    }

    private void validateReference(String field, String reference, String cellId,
                                   Set<String> ids, List<String> evidence) {
        if (reference != null && !reference.isBlank() && !ids.contains(reference)) {
            evidence.add("Cell " + cellId + " has missing " + field + " reference: " + reference + ".");
        }
    }

    private void validateRequiredReference(String field, String reference, String cellId,
                                           Set<String> ids, List<String> evidence) {
        if (reference == null || reference.isBlank()) {
            evidence.add("Edge " + cellId + " is missing " + field + ".");
        } else {
            validateReference(field, reference, cellId, ids, evidence);
        }
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

    private void compare(String field, Object expected, Object actual, List<String> evidence) {
        if (expected != null && !Objects.equals(expected, actual)) {
            evidence.add("Expected " + field + " " + expected + " but got " + actual + ".");
        }
    }
}
