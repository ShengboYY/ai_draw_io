package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import lombok.EqualsAndHashCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasSummaryData;
import org.zipp.ai.domain.agent.model.valobj.analysis.DiagramType;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.canvas.routing.TargetedEdgeRouter;
import org.zipp.ai.types.util.SecretLogSanitizer;

import javax.annotation.Resource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
public class DrawioCanvasMcpService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DrawioCanvasMcpService.class);

    private static final int MAX_CONTINUATION_BUFFER_CHARS = 600_000;

    private final DrawioCanvasXmlToolkit xmlToolkit = new DrawioCanvasXmlToolkit();
    private final TargetedEdgeRouter targetedEdgeRouter = new TargetedEdgeRouter();
    private final ConcurrentMap<String, StringBuilder> continuationBuffers = new ConcurrentHashMap<>();
    @Value("${zipp.canvas.targeted-edge-router-v2-enabled:false}")
    private boolean targetedEdgeRouterV2Enabled;
    @Resource
    private ICanvasStateStore canvasStateStore;

    @Tool(name = DrawioCanvasToolNames.CREATE_DIAGRAM, description = "Create a new Draw.io diagram from mxCell XML fragments or a complete mxGraphModel. Follow the Global Draw.io Layout Contract for the layout mode you chose: in grid-flow mode keep nodes on a stable grid and choose edge routing by relationship/layout semantics — straight edgeStyle=none for hierarchy/dependency/fan-out, orthogonal routing with explicit exit/entry ports and waypoints for dense workflow/network wiring; never reuse the same node-side anchor for multiple connectors. In radial mode place nodes on ring coordinates and keep spokes as port-less edgeStyle=none straight lines. Avoid relying on later review repair for first-draft readability. The backend wraps, validates, and streams the final canvas.")
    public DrawioToolResponse createDiagram(DrawioXmlRequest request) {
        // Preserve the model's original routing so auto-reroute can be tested independently.
        DrawioToolResponse response = drawioDone(request.getXml());
        logXmlToolResult(DrawioCanvasToolNames.CREATE_DIAGRAM, request.getReason(), request.getXml(), response.getType(), response.getContent());
        return response;
    }

    public DrawioToolResponse displayDiagram(DrawioXmlRequest request) {
        logXmlToolCall(DrawioCanvasToolNames.DISPLAY_DIAGRAM, request.getReason(), request.getXml());
        return drawioDone(request.getXml());
    }

    public DrawioToolResponse appendDiagram(DrawioXmlRequest request) {
        logXmlToolCall(DrawioCanvasToolNames.APPEND_DIAGRAM, request.getReason(), request.getXml());
        return drawioDone(request.getXml());
    }

    public DrawioToolResponse editDiagram(DrawioXmlRequest request) {
        logXmlToolCall(DrawioCanvasToolNames.EDIT_DIAGRAM, request.getReason(), request.getXml());
        return drawioDone(request.getXml());
    }

    @Tool(name = DrawioCanvasToolNames.MODIFY_DIAGRAM, description = "Modify the current Draw.io canvas with local cell changes only. Follow the Global Draw.io Layout Contract for changed cells: preserve stable ids and unrelated geometry, keep node spacing readable, and match the diagram's existing layout mode — in grid-flow layouts choose routing by relationship/layout semantics, using straight edgeStyle=none for hierarchy/dependency/fan-out and orthogonal routing with explicit exit/entry ports plus waypoints for dense workflow/network wiring; never reuse the same node-side anchor for multiple connectors. In radial layouts keep spokes as port-less edgeStyle=none lines on ring coordinates. Use mode=patch for changed mxCell fragments, append for additions, or replace_cells for id-based replacements. Use create_diagram for full redraws or full canvas replacement.")
    public DrawioMutationResponse modifyDiagram(ModifyDiagramRequest request) {
        String mode = resolveModifyMode(request);
        DrawioMutationResponse response = new DrawioMutationResponse();
        if ("unsupported".equals(mode)) {
            response = rejectedModifyResponse();
            logModifyToolResult(request, mode, response);
            return response;
        }
        if ("patch".equals(mode)) {
            response.setType(DrawioCanvasToolNames.PATCH_CELLS);
            response.setCells(request.getCells());
            // Feedback needs the merged canvas; without the current xml the backend merges
            // stream-side and the loop gets a plain applied/finish signal.
            if (StringUtils.isNotBlank(request.getXml())) {
                CanvasAnalysis mergedAnalysis = xmlToolkit.analyze(
                        xmlToolkit.replaceCells(request.getXml(), request.getCells()));
                response.setAnalysis(CanvasAnalysisResponse.from(mergedAnalysis));
                response.setRepairBrief(DrawioRepairBriefComposer.compose(mergedAnalysis));
            } else {
                response.setRepairBrief(DrawioRepairBriefComposer.FINISH_SIGNAL);
            }
            logModifyToolResult(request, mode, response);
            return response;
        }
        if ("append".equals(mode)) {
            String merged = xmlToolkit.replaceCells(request.getXml(), request.getCells());
            CanvasAnalysis mergedAnalysis = xmlToolkit.analyze(merged);
            response.setType(DrawioCanvasToolNames.PATCH_CELLS);
            response.setCells(request.getCells());
            response.setAnalysis(CanvasAnalysisResponse.from(mergedAnalysis));
            response.setRepairBrief(DrawioRepairBriefComposer.compose(mergedAnalysis));
            logModifyToolResult(request, mode, response);
            return response;
        }

        // Tool output is only a working candidate; the Mutation Gate canonicalizes the final one.
        String base = xmlToolkit.replaceCells(request.getXml(), request.getCells());
        CanvasAnalysis baseAnalysis = xmlToolkit.analyze(base);
        response.setType("drawio_done");
        response.setContent(base);
        response.setAnalysis(CanvasAnalysisResponse.from(baseAnalysis));
        response.setRepairBrief(DrawioRepairBriefComposer.compose(baseAnalysis));
        logModifyToolResult(request, mode, response);
        return response;
    }

    @Tool(name = DrawioCanvasToolNames.OPTIMIZE_DIAGRAM, description = "Optimize Draw.io layout, spacing, readability, or edge routing under the Global Draw.io Layout Contract, preserving the diagram's layout mode and relationship/layout semantics. In grid-flow layouts keep straight edgeStyle=none relationships straight, never reuse the same node-side anchor, and use orthogonal routing with explicit exit/entry ports, distinct tracks, and waypoints only for dense workflow/network wiring or obstacle avoidance; in radial layouts even out ring spacing instead and never straighten edgeStyle=none/curved spokes. Use mode=route_only with non-empty targetEdgeIds and diagramType for scoped edge patches, or layout_optimize for a complete optimized mxGraphModel.")
    public DrawioMutationResponse optimizeDiagram(OptimizeDiagramRequest request) {
        boolean routeOnly = routeOnlyMode(request);
        Set<String> targetEdgeIds = routeOnly ? targetEdgeIds(request) : Set.of();
        if (routeOnly && targetEdgeIds.isEmpty()) {
            // A missing scope must fail closed; treating it as "all edges" can destroy manual routes.
            DrawioMutationResponse response = rejectedOptimizeResponse(
                    "optimize_diagram mode=route_only requires non-empty targetEdgeIds.");
            logOptimizeToolResult(request, response);
            return response;
        }
        String sourceXml = resolveOptimizableXml(request);
        if (routeOnly) {
            Set<String> existingEdgeIds = xmlToolkit.inspect(sourceXml).getCells().stream()
                    .filter(cell -> "edge".equals(cell.getKind()))
                    .map(DrawioCanvasXmlToolkit.CellInfo::getId)
                    .collect(Collectors.toSet());
            List<String> invalidTargetIds = targetEdgeIds.stream()
                    .filter(id -> !existingEdgeIds.contains(id))
                    .sorted()
                    .toList();
            if (!invalidTargetIds.isEmpty()) {
                DrawioMutationResponse response = rejectedOptimizeResponse(
                        "targetEdgeIds must reference existing edge mxCells; invalid ids: " + invalidTargetIds);
                logOptimizeToolResult(request, response);
                return response;
            }
        }
        TargetedEdgeRouter.RoutingResult routingResult = null;
        String content = sourceXml;
        if (routeOnly) {
            if (targetedEdgeRouterV2Enabled) {
                routingResult = targetedEdgeRouter.route(
                        sourceXml, DiagramType.from(request.getDiagramType()), targetEdgeIds);
                content = routingResult.xml();
            } else {
                content = xmlToolkit.routeEdges(sourceXml, targetEdgeIds);
            }
        }
        DrawioMutationResponse response = routeOnly
                ? edgePatchResponse(xmlToolkit.edgeCells(content, targetEdgeIds), content)
                : drawioMutationDone(content);
        if (routingResult != null && routingResult.status() == TargetedEdgeRouter.Status.NO_SAFE_CANDIDATE) {
            // This is an explicit non-mutation result. Returning patch_cells here would make the
            // post-processor apply an unchanged edge and hide the fail-closed signal from the loop.
            response.setType(DrawioCanvasToolNames.NO_SAFE_CANDIDATE);
            response.setCells(null);
            response.setContent(null);
            response.setRepairBrief("NO_SAFE_CANDIDATE: " + routingResult.reason());
        }
        logOptimizeToolResult(request, response);
        return response;
    }

    // Internal analysis entry point; drawer prompts receive Canvas Issues automatically.
    public InspectCanvasResponse inspectCanvas(DrawioXmlRequest request) {
        DrawioCanvasXmlToolkit.CanvasInspection inspection = xmlToolkit.inspect(request.getXml());
        List<CellMatch> nodes = inspection.getCells().stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .map(CellMatch::from)
                .toList();
        List<CellMatch> edges = inspection.getCells().stream()
                .filter(cell -> "edge".equals(cell.getKind()))
                .map(CellMatch::from)
                .toList();
        List<OverlapMatch> overlaps = xmlToolkit.detectOverlaps(request.getXml())
                .stream()
                .map(OverlapMatch::from)
                .toList();

        InspectCanvasResponse response = new InspectCanvasResponse();
        response.setType("canvas_inspection");
        response.setValid(inspection.isValid());
        response.setSeverity(inspection.getSeverity());
        response.setSummary(inspection.getSummary());
        response.setNodeCount(nodes.size());
        response.setEdgeCount(edges.size());
        response.setNodes(nodes);
        response.setEdges(edges);
        response.setIssues(inspection.getIssues());
        response.setOverlaps(overlaps);
        response.setContent(inspection.isValid() ? "Canvas inspection passed lightweight validation." : String.join("; ", inspection.getIssues()));
        logInspectToolResult(request, response);
        return response;
    }

    public DrawioValidationResponse validateDiagram(DrawioXmlRequest request) {
        logXmlToolCall("validate_diagram", request.getReason(), request.getXml());
        DrawioCanvasXmlToolkit.CanvasInspection inspection = xmlToolkit.inspect(request.getXml());
        DrawioValidationResponse response = new DrawioValidationResponse();
        response.setType("validation_result");
        response.setValid(inspection.isValid());
        response.setSeverity(inspection.getSeverity());
        response.setIssues(inspection.getIssues());
        response.setContent(inspection.isValid() ? "Diagram XML passed lightweight validation." : String.join("; ", inspection.getIssues()));
        return response;
    }

    public DrawioCanvasStateResponse getCanvasState(DrawioXmlRequest request) {
        logXmlToolCall("get_canvas_state", request.getReason(), request.getXml());
        DrawioCanvasXmlToolkit.CanvasInspection inspection = xmlToolkit.inspect(request.getXml());
        List<CellMatch> nodes = inspection.getCells().stream()
                .filter(cell -> "node".equals(cell.getKind()))
                .map(CellMatch::from)
                .toList();
        List<CellMatch> edges = inspection.getCells().stream()
                .filter(cell -> "edge".equals(cell.getKind()))
                .map(CellMatch::from)
                .toList();

        DrawioCanvasStateResponse response = new DrawioCanvasStateResponse();
        response.setType("canvas_state");
        response.setValid(inspection.isValid());
        response.setSummary(inspection.getSummary());
        response.setNodeCount(nodes.size());
        response.setEdgeCount(edges.size());
        response.setNodes(nodes);
        response.setEdges(edges);
        response.setIssues(inspection.getIssues());
        return response;
    }

    public FindCellsResponse findCells(FindCellsRequest request) {
        log.info("[drawio-tool] name=find_cells query={} xmlChars={}",
                sanitizeLogValue(request.getQuery()), textLength(request.getXml()));
        List<CellMatch> matches = xmlToolkit.findCells(request.getXml(), request.getQuery())
                .stream()
                .map(CellMatch::from)
                .toList();

        FindCellsResponse response = new FindCellsResponse();
        response.setType("cell_matches");
        response.setMatches(matches);
        response.setContent(matches.isEmpty() ? "No matching cells found." : "Found " + matches.size() + " matching cells.");
        return response;
    }

    public DrawioToolResponse updateCells(UpdateCellsRequest request) {
        log.info("[drawio-tool] name=update_cells reason={} xmlChars={} cellsChars={}",
                sanitizeLogValue(request.getReason()), textLength(request.getXml()), textLength(request.getCells()));
        return drawioDone(xmlToolkit.replaceCells(request.getXml(), request.getCells()));
    }

    public DrawioCellPatchResponse patchCells(PatchCellsRequest request) {
        log.info("[drawio-tool] name=patch_cells reason={} cellsChars={}",
                sanitizeLogValue(request.getReason()), textLength(request.getCells()));
        DrawioCellPatchResponse response = new DrawioCellPatchResponse();
        response.setType(DrawioCanvasToolNames.PATCH_CELLS);
        response.setCells(request.getCells());
        return response;
    }

    public OverlapReportResponse detectOverlaps(DrawioXmlRequest request) {
        logXmlToolCall("detect_overlaps", request.getReason(), request.getXml());
        List<OverlapMatch> overlaps = xmlToolkit.detectOverlaps(request.getXml())
                .stream()
                .map(OverlapMatch::from)
                .toList();

        OverlapReportResponse response = new OverlapReportResponse();
        response.setType("overlap_report");
        response.setOverlaps(overlaps);
        response.setContent(overlaps.isEmpty() ? "No overlapping nodes found." : "Found " + overlaps.size() + " overlapping node pairs.");
        return response;
    }

    public DrawioToolResponse routeEdges(DrawioXmlRequest request) {
        logXmlToolCall(DrawioCanvasToolNames.ROUTE_EDGES, request.getReason(), request.getXml());
        return drawioDone(xmlToolkit.routeEdges(request.getXml()));
    }

    public DrawioContinuationResponse continueDiagram(ContinueDiagramRequest request) {
        log.info("[drawio-tool] name=continue_diagram continuationId={} fragmentChars={} done={} reset={}",
                sanitizeLogValue(request.getContinuationId()), textLength(request.getXmlFragment()), request.isDone(), request.isReset());
        String continuationId = normalizeContinuationId(request.getContinuationId());
        if (request.isReset()) {
            continuationBuffers.remove(continuationId);
        }

        StringBuilder buffer = continuationBuffers.computeIfAbsent(continuationId, ignored -> new StringBuilder());
        String fragment = request.getXmlFragment() == null ? "" : request.getXmlFragment();
        if (buffer.length() + fragment.length() > MAX_CONTINUATION_BUFFER_CHARS) {
            continuationBuffers.remove(continuationId);
            return continuationResponse("continuation_result", continuationId, false, 0, "",
                    "Continuation buffer exceeded the maximum allowed XML size and was reset.");
        }

        buffer.append(fragment);
        if (!request.isDone()) {
            return continuationResponse("continuation_result", continuationId, false, buffer.length(), "",
                    "Buffered XML fragment.");
        }

        String graphModel = toGraphModel(buffer.toString());
        continuationBuffers.remove(continuationId);
        return continuationResponse("drawio_done", continuationId, true, graphModel.length(), graphModel,
                "Completed buffered Draw.io XML.");
    }

    private DrawioToolResponse drawioDone(String xml) {
        // Tool output remains a working candidate. The post-processor may analyze it for Drawer
        // feedback, but only CanvasMutationGate may canonicalize and accept the final candidate.
        return drawioDoneContent(xml);
    }

    private DrawioToolResponse drawioDoneContent(String content) {
        return drawioDoneContent(content, xmlToolkit.analyze(content));
    }

    private DrawioToolResponse drawioDoneContent(String content, CanvasAnalysis analysis) {
        DrawioToolResponse response = new DrawioToolResponse();
        response.setType("drawio_done");
        response.setContent(content);
        response.setAnalysis(CanvasAnalysisResponse.from(analysis));
        response.setRepairBrief(DrawioRepairBriefComposer.compose(analysis));
        return response;
    }

    private DrawioMutationResponse drawioMutationDone(String content) {
        CanvasAnalysis analysis = xmlToolkit.analyze(content);
        DrawioMutationResponse response = new DrawioMutationResponse();
        response.setType("drawio_done");
        response.setContent(content);
        response.setAnalysis(CanvasAnalysisResponse.from(analysis));
        response.setRepairBrief(DrawioRepairBriefComposer.compose(analysis));
        return response;
    }

    private DrawioMutationResponse edgePatchResponse(String cells, String mergedContent) {
        if (cells == null || cells.isBlank()) {
            return drawioMutationDone(mergedContent);
        }
        CanvasAnalysis analysis = xmlToolkit.analyze(mergedContent);
        DrawioMutationResponse response = new DrawioMutationResponse();
        response.setType(DrawioCanvasToolNames.PATCH_CELLS);
        response.setCells(cells);
        response.setAnalysis(CanvasAnalysisResponse.from(analysis));
        response.setRepairBrief(DrawioRepairBriefComposer.compose(analysis));
        return response;
    }

    private CanvasAnalysisResponse analysis(String xml) {
        return CanvasAnalysisResponse.from(xmlToolkit.analyze(xml));
    }

    private boolean routeOnlyMode(OptimizeDiagramRequest request) {
        return "route_only".equals(String.valueOf(request.getMode()).trim());
    }

    private Set<String> targetEdgeIds(OptimizeDiagramRequest request) {
        if (request == null || request.getTargetEdgeIds() == null) {
            return Set.of();
        }
        return request.getTargetEdgeIds().stream()
                .filter(StringUtils::isNotBlank)
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    private String resolveOptimizableXml(OptimizeDiagramRequest request) {
        if (request == null) {
            return "";
        }
        if (request.getXml() != null && !request.getXml().isBlank()) {
            return request.getXml();
        }
        if (canvasStateStore == null || isBlank(request.getUserId()) || isBlank(request.getDiagramId())) {
            return "";
        }
        try {
            Optional<CanvasState> stored = canvasStateStore.find(request.getUserId(), request.getDiagramId());
            return stored.map(CanvasState::getCurrentXml).filter(xml -> !isBlank(xml)).orElse("");
        } catch (Exception e) {
            log.warn("Failed to load canvas state for optimize_diagram. userId:{} diagramId:{}",
                    SecretLogSanitizer.maskCapability(request.getUserId()), sanitizeLogValue(request.getDiagramId()), e);
            return "";
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String resolveModifyMode(ModifyDiagramRequest request) {
        String mode = request.getMode() == null ? "" : request.getMode().trim();
        if ("patch".equals(mode) || "append".equals(mode) || "replace_cells".equals(mode)) {
            return mode;
        }
        if (mode.isEmpty() && request.getCells() != null && !request.getCells().isBlank()) {
            return request.getXml() == null || request.getXml().isBlank() ? "patch" : "replace_cells";
        }
        return "unsupported";
    }

    private DrawioMutationResponse rejectedModifyResponse() {
        DrawioMutationResponse response = new DrawioMutationResponse();
        response.setType("tool_error");
        // Keep full-canvas replacement out of the modify path; redraw intent belongs to create_diagram.
        response.setMessage("modify_diagram only supports patch, append, and replace_cells. Use create_diagram for full redraws or full canvas replacement.");
        return response;
    }

    private DrawioMutationResponse rejectedOptimizeResponse(String message) {
        DrawioMutationResponse response = new DrawioMutationResponse();
        response.setType("tool_error");
        response.setMessage(message);
        return response;
    }

    // Keep tool observability compact; raw XML can be very large and may contain user content.
    private void logXmlToolCall(String toolName, String reason, String xml) {
        log.info("[drawio-tool] name={} reason={} xmlChars={}",
                toolName, sanitizeLogValue(reason), textLength(xml));
    }

    private void logXmlToolResult(String toolName, String reason, String inputXml, String resultType, String outputXml) {
        log.info("[drawio-tool] name={} resultType={} reason={} inputXmlChars={} outputXmlChars={}",
                sanitizeLogValue(toolName), sanitizeLogValue(resultType), sanitizeLogValue(reason),
                textLength(inputXml), textLength(outputXml));
    }

    private void logModifyToolResult(ModifyDiagramRequest request, String resolvedMode, DrawioMutationResponse response) {
        log.info("[drawio-tool] name=modify_diagram requestedMode={} resolvedMode={} resultType={} reason={} inputXmlChars={} cellsChars={} outputXmlChars={} targetId={} targetLabel={}",
                sanitizeLogValue(request.getMode()), sanitizeLogValue(resolvedMode), sanitizeLogValue(response.getType()),
                sanitizeLogValue(request.getReason()), textLength(request.getXml()), textLength(request.getCells()),
                textLength(response.getContent()), sanitizeLogValue(request.getTargetId()), sanitizeLogValue(request.getTargetLabel()));
    }

    private void logOptimizeToolResult(OptimizeDiagramRequest request, DrawioMutationResponse response) {
        log.info("[drawio-tool] name=optimize_diagram mode={} resultType={} reason={} inputXmlChars={} cellsChars={} outputXmlChars={}",
                sanitizeLogValue(request.getMode()), sanitizeLogValue(response.getType()), sanitizeLogValue(request.getReason()),
                textLength(request.getXml()), textLength(response.getCells()), textLength(response.getContent()));
    }

    private void logInspectToolResult(DrawioXmlRequest request, InspectCanvasResponse response) {
        log.info("[drawio-tool] name=inspect_canvas resultType={} reason={} inputXmlChars={} valid={} severity={} nodeCount={} edgeCount={} overlapCount={} issueCount={}",
                sanitizeLogValue(response.getType()), sanitizeLogValue(request.getReason()), textLength(request.getXml()),
                response.isValid(), sanitizeLogValue(response.getSeverity()), response.getNodeCount(), response.getEdgeCount(),
                null == response.getOverlaps() ? 0 : response.getOverlaps().size(),
                null == response.getIssues() ? 0 : response.getIssues().size());
    }

    private int textLength(String text) {
        return text == null ? 0 : text.length();
    }

    private String sanitizeLogValue(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "...";
    }

    private String toGraphModel(String xml) {
        return xmlToolkit.toGraphModel(xml);
    }

    private String normalizeContinuationId(String continuationId) {
        if (continuationId == null || continuationId.trim().isEmpty()) {
            return "default";
        }
        return continuationId.trim();
    }

    private DrawioContinuationResponse continuationResponse(String type,
                                                            String continuationId,
                                                            boolean complete,
                                                            int bufferedLength,
                                                            String content,
                                                            String message) {
        DrawioContinuationResponse response = new DrawioContinuationResponse();
        response.setType(type);
        response.setContinuationId(continuationId);
        response.setComplete(complete);
        response.setBufferedLength(bufferedLength);
        response.setContent(content);
        response.setMessage(message);
        return response;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DrawioXmlRequest {
        @JsonProperty(required = true, value = "xml")
        @JsonPropertyDescription("Draw.io mxCell XML fragments, or a complete mxGraphModel for edit/append/optimize operations.")
        private String xml;

        @JsonProperty(value = "reason")
        @JsonPropertyDescription("Short internal reason for choosing this drawing tool.")
        private String reason;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ModifyDiagramRequest {
        @JsonProperty(value = "mode")
        @JsonPropertyDescription("patch for changed mxCell fragments, append for new cell fragments, or replace_cells to merge cells into current xml. Use create_diagram for full redraws.")
        private String mode;

        @JsonProperty(value = "xml")
        @JsonPropertyDescription("Current Draw.io mxGraphModel XML. Required for replace_cells and append analysis.")
        private String xml;

        @JsonProperty(value = "cells")
        @JsonPropertyDescription("Changed mxCell fragment(s). Required for patch and replace_cells modes.")
        private String cells;

        @JsonProperty(value = "targetId")
        @JsonPropertyDescription("Optional existing target mxCell id when the caller knows it.")
        private String targetId;

        @JsonProperty(value = "targetLabel")
        @JsonPropertyDescription("Optional existing target label when the caller does not know the cell id.")
        private String targetLabel;

        @JsonProperty(value = "reason")
        @JsonPropertyDescription("Short internal reason for this modification.")
        private String reason;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OptimizeDiagramRequest extends DrawioXmlRequest {
        @JsonProperty(value = "mode")
        @JsonPropertyDescription("route_only returns scoped edge mxCell patches and requires targetEdgeIds; layout_optimize returns a complete optimized mxGraphModel.")
        private String mode;

        @JsonProperty(value = "targetEdgeIds")
        @JsonPropertyDescription("Existing edge mxCell ids to reroute. Required and non-empty when mode=route_only.")
        private List<String> targetEdgeIds;

        @JsonProperty(value = "diagramType")
        @JsonPropertyDescription("Optional quality profile for route_only, such as flowchart, architecture, or state. Unknown profiles fail closed in router v2.")
        private String diagramType;

        @JsonProperty(value = "userId")
        @JsonPropertyDescription("Optional owner id used with diagramId to load the current canvas when xml is omitted.")
        private String userId;

        @JsonProperty(value = "diagramId")
        @JsonPropertyDescription("Optional diagram id used with userId to load the current canvas when xml is omitted.")
        private String diagramId;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DrawioToolResponse {
        @JsonProperty(required = true, value = "type")
        @JsonPropertyDescription("Always drawio_done so the streaming layer can update the canvas.")
        private String type;

        @JsonProperty(required = true, value = "content")
        @JsonPropertyDescription("Complete Draw.io mxGraphModel XML ready for the frontend.")
        private String content;

        @JsonProperty(value = "analysis")
        @JsonPropertyDescription("Validation result for this exact returned content, without raw cell XML.")
        private CanvasAnalysisResponse analysis;

        @JsonProperty(value = "repairBrief")
        @JsonPropertyDescription("Next-step instruction for the drawing loop: numbered repair directives for remaining blocking issues, or the finish signal.")
        private String repairBrief;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DrawioMutationResponse {
        @JsonProperty(required = true, value = "type")
        @JsonPropertyDescription("patch_cells for local fragments, drawio_done for complete updated XML, or no_safe_candidate when no mutation was produced.")
        private String type;

        @JsonProperty(value = "content")
        @JsonPropertyDescription("Complete Draw.io mxGraphModel XML when type=drawio_done.")
        private String content;

        @JsonProperty(value = "cells")
        @JsonPropertyDescription("Changed mxCell fragment(s) when type=patch_cells.")
        private String cells;

        @JsonProperty(value = "analysis")
        @JsonPropertyDescription("Validation result for the returned complete content, or for the merged canvas represented by patch_cells.")
        private CanvasAnalysisResponse analysis;

        @JsonProperty(value = "repairBrief")
        @JsonPropertyDescription("Next-step instruction for the drawing loop: numbered repair directives for remaining blocking issues, or the finish signal.")
        private String repairBrief;

        @JsonProperty(value = "message")
        @JsonPropertyDescription("Tool error guidance when type=tool_error.")
        private String message;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FindCellsRequest {
        @JsonProperty(required = true, value = "xml")
        @JsonPropertyDescription("Current complete Draw.io mxGraphModel XML.")
        private String xml;

        @JsonProperty(required = true, value = "query")
        @JsonPropertyDescription("Cell id, label, style keyword, source id, or target id to search for.")
        private String query;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UpdateCellsRequest {
        @JsonProperty(required = true, value = "xml")
        @JsonPropertyDescription("Current complete Draw.io mxGraphModel XML.")
        private String xml;

        @JsonProperty(required = true, value = "cells")
        @JsonPropertyDescription("Replacement mxCell XML fragments or a complete mxGraphModel containing cells to replace/append by id.")
        private String cells;

        @JsonProperty(value = "reason")
        @JsonPropertyDescription("Short internal reason for choosing localized cell updates.")
        private String reason;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PatchCellsRequest {
        @JsonProperty(required = true, value = "cells")
        @JsonPropertyDescription("Only the changed mxCell XML fragment(s), keyed by their existing ids. Never include unchanged cells or a full mxGraphModel.")
        private String cells;

        @JsonProperty(value = "reason")
        @JsonPropertyDescription("Short internal reason for this localized patch.")
        private String reason;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DrawioCellPatchResponse {
        @JsonProperty(required = true, value = "type")
        @JsonPropertyDescription("Always patch_cells so the backend merges the fragment into the current canvas.")
        private String type;

        @JsonProperty(required = true, value = "cells")
        @JsonPropertyDescription("The changed mxCell fragment(s) to merge by id.")
        private String cells;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContinueDiagramRequest {
        @JsonProperty(required = true, value = "continuationId")
        @JsonPropertyDescription("Stable id shared by all fragments for one long diagram response.")
        private String continuationId;

        @JsonProperty(required = true, value = "xmlFragment")
        @JsonPropertyDescription("Next ordered mxCell/XML fragment. Do not repeat previous fragments unless reset=true.")
        private String xmlFragment;

        @JsonProperty(required = true, value = "done")
        @JsonPropertyDescription("false for intermediate fragments; true only for the final fragment.")
        private boolean done;

        @JsonProperty(value = "reset")
        @JsonPropertyDescription("true to discard any existing buffer for this continuationId before appending.")
        private boolean reset;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DrawioContinuationResponse {
        @JsonProperty(required = true, value = "type")
        private String type;

        @JsonProperty(required = true, value = "continuationId")
        private String continuationId;

        @JsonProperty(required = true, value = "complete")
        private boolean complete;

        @JsonProperty(required = true, value = "bufferedLength")
        private int bufferedLength;

        @JsonProperty(value = "content")
        private String content;

        @JsonProperty(required = true, value = "message")
        private String message;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DrawioValidationResponse {
        @JsonProperty(required = true, value = "type")
        private String type;

        @JsonProperty(required = true, value = "valid")
        private boolean valid;

        @JsonProperty(required = true, value = "severity")
        private String severity;

        @JsonProperty(required = true, value = "issues")
        private List<String> issues;

        @JsonProperty(required = true, value = "content")
        private String content;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DrawioCanvasStateResponse {
        @JsonProperty(required = true, value = "type")
        private String type;

        @JsonProperty(required = true, value = "valid")
        private boolean valid;

        @JsonProperty(required = true, value = "summary")
        private String summary;

        @JsonProperty(required = true, value = "nodeCount")
        private int nodeCount;

        @JsonProperty(required = true, value = "edgeCount")
        private int edgeCount;

        @JsonProperty(required = true, value = "nodes")
        private List<CellMatch> nodes;

        @JsonProperty(required = true, value = "edges")
        private List<CellMatch> edges;

        @JsonProperty(value = "issues")
        private List<String> issues;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FindCellsResponse {
        @JsonProperty(required = true, value = "type")
        private String type;

        @JsonProperty(required = true, value = "matches")
        private List<CellMatch> matches;

        @JsonProperty(required = true, value = "content")
        private String content;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OverlapReportResponse {
        @JsonProperty(required = true, value = "type")
        private String type;

        @JsonProperty(required = true, value = "overlaps")
        private List<OverlapMatch> overlaps;

        @JsonProperty(required = true, value = "content")
        private String content;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InspectCanvasResponse {
        @JsonProperty(required = true, value = "type")
        private String type;

        @JsonProperty(required = true, value = "valid")
        private boolean valid;

        @JsonProperty(required = true, value = "severity")
        private String severity;

        @JsonProperty(required = true, value = "summary")
        private String summary;

        @JsonProperty(required = true, value = "nodeCount")
        private int nodeCount;

        @JsonProperty(required = true, value = "edgeCount")
        private int edgeCount;

        @JsonProperty(required = true, value = "nodes")
        private List<CellMatch> nodes;

        @JsonProperty(required = true, value = "edges")
        private List<CellMatch> edges;

        @JsonProperty(required = true, value = "issues")
        private List<String> issues;

        @JsonProperty(required = true, value = "overlaps")
        private List<OverlapMatch> overlaps;

        @JsonProperty(required = true, value = "content")
        private String content;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CellMatch {
        private String id;
        private String label;
        private String kind;
        private String style;
        private String parentId;
        private String source;
        private String target;
        private double x;
        private double y;
        private double width;
        private double height;

        static CellMatch from(DrawioCanvasXmlToolkit.CellInfo cell) {
            CellMatch match = new CellMatch();
            match.setId(cell.getId());
            match.setLabel(cell.getLabel());
            match.setKind(cell.getKind());
            match.setStyle(cell.getStyle());
            match.setParentId(cell.getParentId());
            match.setSource(cell.getSource());
            match.setTarget(cell.getTarget());
            match.setX(cell.getX());
            match.setY(cell.getY());
            match.setWidth(cell.getWidth());
            match.setHeight(cell.getHeight());
            return match;
        }
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OverlapMatch {
        private String sourceId;
        private String targetId;
        private double overlapWidth;
        private double overlapHeight;

        static OverlapMatch from(DrawioCanvasXmlToolkit.OverlapInfo overlap) {
            OverlapMatch match = new OverlapMatch();
            match.setSourceId(overlap.getSourceId());
            match.setTargetId(overlap.getTargetId());
            match.setOverlapWidth(overlap.getOverlapWidth());
            match.setOverlapHeight(overlap.getOverlapHeight());
            return match;
        }
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CanvasAnalysisResponse {
        @JsonProperty(required = true, value = "type")
        private String type;

        @JsonProperty(required = true, value = "valid")
        private boolean valid;

        @JsonProperty(required = true, value = "severity")
        private String severity;

        @JsonProperty(required = true, value = "issues")
        private List<CanvasIssueResponse> issues;

        @JsonProperty(required = true, value = "summary")
        private CanvasSummaryResponse summary;

        static CanvasAnalysisResponse from(CanvasAnalysis analysis) {
            CanvasAnalysisResponse response = new CanvasAnalysisResponse();
            response.setType("validation_result");
            response.setValid(analysis.isValid());
            response.setSeverity(analysis.getSeverity());
            response.setIssues(analysis.getIssues().stream().map(CanvasIssueResponse::from).toList());
            response.setSummary(CanvasSummaryResponse.from(analysis.getSummary()));
            return response;
        }
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CanvasIssueResponse {
        private String type;
        private String category;
        private String severity;
        private List<String> targetCellIds;
        private String message;
        private String repairability;

        static CanvasIssueResponse from(CanvasAnalysisIssue issue) {
            CanvasIssueResponse response = new CanvasIssueResponse();
            response.setType(issue.getType().name());
            response.setCategory(issue.getCategory());
            response.setSeverity(issue.getSeverity());
            response.setTargetCellIds(issue.getTargetCellIds());
            response.setMessage(issue.getMessage());
            response.setRepairability(issue.getRepairability());
            return response;
        }
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CanvasSummaryResponse {
        private int nodeCount;
        private int edgeCount;
        private double x;
        private double y;
        private double width;
        private double height;
        private String summary;

        static CanvasSummaryResponse from(CanvasSummaryData summary) {
            CanvasSummaryResponse response = new CanvasSummaryResponse();
            response.setNodeCount(summary.getNodeCount());
            response.setEdgeCount(summary.getEdgeCount());
            response.setX(summary.getX());
            response.setY(summary.getY());
            response.setWidth(summary.getWidth());
            response.setHeight(summary.getHeight());
            response.setSummary(summary.getSummary());
            return response;
        }
    }
}
