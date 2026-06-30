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

    @Tool(name = "display_diagram", description = "Create a new Draw.io diagram from mxCell XML fragments or a complete mxGraphModel.")
    public DrawioToolResponse displayDiagram(DrawioXmlRequest request) {
        logXmlToolCall("display_diagram", request.getReason(), request.getXml());
        return drawioDone(request.getXml());
    }

    @Tool(name = "append_diagram", description = "Append new Draw.io cells to the current diagram. Return the complete updated mxGraphModel.")
    public DrawioToolResponse appendDiagram(DrawioXmlRequest request) {
        logXmlToolCall("append_diagram", request.getReason(), request.getXml());
        return drawioDone(request.getXml());
    }

    @Tool(name = "edit_diagram", description = "Apply a localized Draw.io edit. Return the complete updated mxGraphModel with unrelated cells preserved.")
    public DrawioToolResponse editDiagram(DrawioXmlRequest request) {
        logXmlToolCall("edit_diagram", request.getReason(), request.getXml());
        return drawioDone(request.getXml());
    }

    @Tool(name = "optimize_diagram", description = "Optimize Draw.io layout, spacing, readability, or edge routing. Return the complete optimized mxGraphModel.")
    public DrawioToolResponse optimizeDiagram(DrawioXmlRequest request) {
        logXmlToolCall("optimize_diagram", request.getReason(), request.getXml());
        return drawioDone(request.getXml());
    }

    @Tool(name = "validate_diagram", description = "Mechanically validate Draw.io XML for parse errors, duplicate ids, missing geometry, empty diagrams, and broken edge source/target references.")
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

    @Tool(name = "get_canvas_state", description = "Summarize the current Draw.io XML as node/edge counts plus searchable cell metadata.")
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

    @Tool(name = "find_cells", description = "Find Draw.io cells by id, label, style, source id, or target id. Use before local edits when the target id is uncertain.")
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

    @Tool(name = "update_cells", description = "Replace or append specific mxCell elements by id and return the complete updated mxGraphModel. Use for localized component edits.")
    public DrawioToolResponse updateCells(UpdateCellsRequest request) {
        log.info("[drawio-tool] name=update_cells reason={} xmlChars={} cellsChars={}",
                sanitizeLogValue(request.getReason()), textLength(request.getXml()), textLength(request.getCells()));
        return drawioDone(xmlToolkit.replaceCells(request.getXml(), request.getCells()));
    }

    @Tool(name = "patch_cells", description = "Minimal local edit for patch_existing (rename, recolor, restyle, move one or a few existing cells). Return ONLY the changed mxCell fragment(s) keyed by their existing ids. Do NOT include unchanged cells and do NOT resend the full mxGraphModel; the backend merges your fragments into the current canvas.")
    public DrawioCellPatchResponse patchCells(PatchCellsRequest request) {
        log.info("[drawio-tool] name=patch_cells reason={} cellsChars={}",
                sanitizeLogValue(request.getReason()), textLength(request.getCells()));
        DrawioCellPatchResponse response = new DrawioCellPatchResponse();
        response.setType("cell_patch");
        response.setCells(request.getCells());
        return response;
    }

    @Tool(name = "detect_overlaps", description = "Detect overlapping non-text Draw.io node boxes and return the involved cell ids.")
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

    @Tool(name = "route_edges", description = "Normalize connected Draw.io edges to orthogonal routing with side ports and waypoints. Does not move nodes.")
    public DrawioToolResponse routeEdges(DrawioXmlRequest request) {
        logXmlToolCall("route_edges", request.getReason(), request.getXml());
        return drawioDone(xmlToolkit.routeEdges(request.getXml()));
    }

    @Tool(name = "continue_diagram", description = "Submit long Draw.io XML in ordered fragments. Intermediate fragments buffer only; the final fragment returns a complete mxGraphModel.")
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
        @JsonPropertyDescription("Always cell_patch so the backend merges the fragment into the current canvas.")
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
