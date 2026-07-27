package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps one full working candidate in ADK state while mutation tools return compact local patches.
 * The candidate remains unaccepted until the stream finalizer sends it through CanvasMutationGate.
 */
@Slf4j
public class DrawioMutationResultPostProcessor {

    public static final String DRAFT_DIAGRAM_STATE_KEY = "draft_diagram";
    public static final String DIAGRAM_TYPE_STATE_KEY = "diagram_type";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DrawioCanvasXmlToolkit xmlToolkit = new DrawioCanvasXmlToolkit();
    private final DrawioVisualRepairOperationApplier visualRepairApplier =
            new DrawioVisualRepairOperationApplier();

    public Map<String, Object> process(String toolName,
                                       Map<String, Object> args,
                                       Map<String, Object> response,
                                       Map<String, Object> state) {
        return processWithStatus(toolName, args, response, state).response();
    }

    public ProcessResult processWithStatus(String toolName,
                                           Map<String, Object> args,
                                           Map<String, Object> response,
                                           Map<String, Object> state) {
        Map<String, Object> processed = response == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(response);
        if (!DrawioCanvasToolNames.CONSOLIDATED_TOOL_NAMES.contains(toolName)
                || "tool_error".equals(stringValue(processed.get("type")))
                || DrawioCanvasToolNames.NO_SAFE_CANDIDATE.equals(stringValue(processed.get("type")))) {
            return new ProcessResult(processed, false);
        }

        String candidate;
        if (DrawioCanvasToolNames.APPLY_VISUAL_REPAIR.equals(toolName)) {
            String currentDraft = state == null ? "" : stringValue(state.get(DRAFT_DIAGRAM_STATE_KEY));
            VisualRepairMcpService.ApplyVisualRepairRequest request;
            try {
                request = OBJECT_MAPPER.convertValue(
                        args == null ? Map.of() : args,
                        VisualRepairMcpService.ApplyVisualRepairRequest.class);
            } catch (IllegalArgumentException invalidRequest) {
                log.info("[visual-repair-tool] event=batch_evaluated runId={} outcome=rejected reason=invalid_arguments",
                        currentRunId());
                return rejectedVisualRepair(processed, "visual repair arguments are invalid");
            }
            DrawioVisualRepairOperationApplier.ApplyResult result =
                    visualRepairApplier.apply(currentDraft, request);
            int operationCount = request.getRepairs() == null ? 0 : request.getRepairs().size();
            long targetCount = request.getRepairs() == null ? 0 : request.getRepairs().stream()
                    .filter(java.util.Objects::nonNull)
                    .flatMap(operation -> operation.getTargetCellIds() == null
                            ? java.util.stream.Stream.empty()
                            : operation.getTargetCellIds().stream())
                    .filter(StringUtils::isNotBlank)
                    .distinct()
                    .count();
            log.info("[visual-repair-tool] event=batch_evaluated runId={} operations={} targetCells={} outcome={} reason={}",
                    currentRunId(), operationCount, targetCount,
                    result.applied() ? "applied" : "rejected",
                    result.applied() ? "none" : StringUtils.left(result.rejectionReason(), 160));
            if (!result.applied()) {
                return rejectedVisualRepair(processed, result.rejectionReason());
            }
            // Return only changed cells to the model/frontend; the full candidate remains server-owned.
            processed.put("type", DrawioCanvasToolNames.PATCH_CELLS);
            processed.put("cells", result.changedCells());
            processed.remove("content");
            candidate = result.candidateXml();
        } else {
            candidate = workingCandidate(args, processed, state);
        }
        if (StringUtils.isBlank(candidate)) {
            return new ProcessResult(processed, false);
        }

        // Analysis is read-only feedback for the Drawer loop; acceptance and canonicalization belong
        // exclusively to CanvasMutationGate when the latest working candidate is finalized.
        DiagramType diagramType = DiagramType.from(state == null
                ? null
                : stringValue(state.get(DIAGRAM_TYPE_STATE_KEY)));
        CanvasAnalysis analysis = xmlToolkit.analyze(candidate, diagramType);
        processed.put("analysis", OBJECT_MAPPER.convertValue(
                DrawioCanvasMcpService.CanvasAnalysisResponse.from(analysis), MAP_TYPE));
        processed.put("repairBrief", DrawioRepairBriefComposer.compose(analysis));
        if (state != null) {
            state.put(DRAFT_DIAGRAM_STATE_KEY, candidate);
        }
        return new ProcessResult(processed, true);
    }

    private ProcessResult rejectedVisualRepair(Map<String, Object> processed, String reason) {
        String safeReason = StringUtils.defaultIfBlank(reason, "visual repair produced no safe candidate");
        processed.put("type", DrawioCanvasToolNames.NO_SAFE_CANDIDATE);
        processed.put("repairBrief", "NO_SAFE_CANDIDATE: " + safeReason);
        processed.put("message", safeReason);
        processed.remove("content");
        processed.remove("cells");
        return new ProcessResult(processed, false);
    }

    private String workingCandidate(Map<String, Object> args,
                                    Map<String, Object> response,
                                    Map<String, Object> state) {
        String completeContent = stringValue(response.get("content"));
        if (StringUtils.isNotBlank(completeContent)) {
            return completeContent;
        }

        String cells = stringValue(response.get("cells"));
        if (StringUtils.isBlank(cells)) {
            return "";
        }
        String currentDraft = state == null ? "" : stringValue(state.get(DRAFT_DIAGRAM_STATE_KEY));
        String argumentXml = args == null ? "" : stringValue(args.get("xml"));
        String base = StringUtils.defaultIfBlank(currentDraft, argumentXml);
        return StringUtils.isBlank(base) ? "" : xmlToolkit.replaceCells(base, cells);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String currentRunId() {
        return AgentUsageTelemetryContext.current()
                .map(AgentUsageTelemetryContext.RunContext::runId)
                .filter(StringUtils::isNotBlank)
                .orElse("unbound");
    }

    /** Separates an acknowledged payload type from a mutation merged into the working draft. */
    public record ProcessResult(Map<String, Object> response, boolean mutationApplied) {
    }
}
