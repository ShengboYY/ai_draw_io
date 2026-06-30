package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class DrawioCanvasMcpService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DrawioCanvasMcpService.class);

    private static final int MAX_CONTINUATION_BUFFER_CHARS = 600_000;

    private final DrawioCanvasXmlToolkit xmlToolkit = new DrawioCanvasXmlToolkit();
    private final ConcurrentMap<String, StringBuilder> continuationBuffers = new ConcurrentHashMap<>();

    @Tool(name = DrawioCanvasToolNames.CREATE_DIAGRAM, description = "Create a new Draw.io diagram from mxCell XML fragments or a complete mxGraphModel. The backend wraps, validates, and streams the final canvas.")
    public DrawioToolResponse createDiagram(DrawioXmlRequest request) {
        logXmlToolCall(DrawioCanvasToolNames.CREATE_DIAGRAM, request.getReason(), request.getXml());
        return drawioDone(request.getXml());
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

    @Tool(name = DrawioCanvasToolNames.MODIFY_DIAGRAM, description = "Modify the current Draw.io canvas. Use mode=patch for changed mxCell fragments, replace_cells for id-based replacements, or full_xml for a complete updated mxGraphModel.")
    public DrawioMutationResponse modifyDiagram(ModifyDiagramRequest request) {
        log.info("[drawio-tool] name=modify_diagram mode={} reason={} xmlChars={} cellsChars={}",
                sanitizeLogValue(request.getMode()), sanitizeLogValue(request.getReason()),
                textLength(request.getXml()), textLength(request.getCells()));
        String mode = resolveModifyMode(request);
        DrawioMutationResponse response = new DrawioMutationResponse();
        if ("patch".equals(mode)) {
            response.setType(DrawioCanvasToolNames.PATCH_CELLS);
            response.setCells(request.getCells());
            return response;
        }

        String content = "replace_cells".equals(mode)
                ? xmlToolkit.replaceCells(request.getXml(), request.getCells())
                : toGraphModel(request.getXml());
        response.setType("drawio_done");
        response.setContent(content);
        return response;
    }

    @Tool(name = DrawioCanvasToolNames.OPTIMIZE_DIAGRAM, description = "Optimize Draw.io layout, spacing, readability, or edge routing. The backend normalizes connected edges before returning the optimized mxGraphModel.")
    public DrawioToolResponse optimizeDiagram(DrawioXmlRequest request) {
        logXmlToolCall(DrawioCanvasToolNames.OPTIMIZE_DIAGRAM, request.getReason(), request.getXml());
        return drawioDone(xmlToolkit.routeEdges(request.getXml()));
    }

    @Tool(name = DrawioCanvasToolNames.INSPECT_CANVAS, description = "Inspect Draw.io XML once and return validation, node/edge state, overlap data, and actionable issues.")
    public InspectCanvasResponse inspectCanvas(DrawioXmlRequest request) {
        logXmlToolCall(DrawioCanvasToolNames.INSPECT_CANVAS, request.getReason(), request.getXml());
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
        DrawioToolResponse response = new DrawioToolResponse();
        response.setType("drawio_done");
        response.setContent(toGraphModel(xml));
        return response;
    }

    private String resolveModifyMode(ModifyDiagramRequest request) {
        String mode = request.getMode() == null ? "" : request.getMode().trim();
        if ("patch".equals(mode) || "replace_cells".equals(mode) || "full_xml".equals(mode)) {
            return mode;
        }
        if (request.getCells() != null && !request.getCells().isBlank()) {
            return request.getXml() == null || request.getXml().isBlank() ? "patch" : "replace_cells";
        }
        return "full_xml";
    }

    // Keep tool observability compact; raw XML can be very large and may contain user content.
    private void logXmlToolCall(String toolName, String reason, String xml) {
        log.info("[drawio-tool] name={} reason={} xmlChars={}",
                toolName, sanitizeLogValue(reason), textLength(xml));
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
        @JsonPropertyDescription("patch for changed mxCell fragments, replace_cells to merge cells into current xml, or full_xml for a complete updated mxGraphModel.")
        private String mode;

        @JsonProperty(value = "xml")
        @JsonPropertyDescription("Current or complete Draw.io mxGraphModel XML. Required for replace_cells and full_xml modes.")
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DrawioToolResponse {
        @JsonProperty(required = true, value = "type")
        @JsonPropertyDescription("Always drawio_done so the streaming layer can update the canvas.")
        private String type;

        @JsonProperty(required = true, value = "content")
        @JsonPropertyDescription("Complete Draw.io mxGraphModel XML ready for the frontend.")
        private String content;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DrawioMutationResponse {
        @JsonProperty(required = true, value = "type")
        @JsonPropertyDescription("patch_cells for local fragments or drawio_done for complete updated XML.")
        private String type;

        @JsonProperty(value = "content")
        @JsonPropertyDescription("Complete Draw.io mxGraphModel XML when type=drawio_done.")
        private String content;

        @JsonProperty(value = "cells")
        @JsonPropertyDescription("Changed mxCell fragment(s) when type=patch_cells.")
        private String cells;
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
}
