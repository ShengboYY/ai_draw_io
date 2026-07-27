package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Narrow tool surface for the dedicated visual repair agent.
 * The actual patch is built from server-owned canvas state by the result post-processor.
 */
@Service
public class VisualRepairMcpService {

    static final int MAX_OPERATIONS = 3;

    @Tool(name = DrawioCanvasToolNames.APPLY_VISUAL_REPAIR, description = """
            Apply one atomic batch of one to three authorized visual repairs to the current Draw.io canvas.
            Use SET_GEOMETRY to move or resize one node, SET_STYLE to update whitelisted presentation
            properties on one cell, REROUTE_EDGE to reroute one existing edge without changing endpoints,
            RECONNECT_EDGE to fill a missing endpoint on one explicitly grounded edge, ALIGN_CELLS to align two or more
            nodes to the first target, or DISTRIBUTE_CELLS to space three or more nodes evenly.
            Do not submit XML, add or delete cells, change labels, or alter unrelated cells. The backend
            loads the current canvas, validates every target and operation, applies the batch atomically,
            and enforces the server-owned cell/field authorization before saving.
            """)
    public DrawioCanvasMcpService.DrawioMutationResponse applyVisualRepair(ApplyVisualRepairRequest request) {
        String rejection = validateRequestShape(request);
        DrawioCanvasMcpService.DrawioMutationResponse response =
                new DrawioCanvasMcpService.DrawioMutationResponse();
        if (StringUtils.isNotBlank(rejection)) {
            response.setType(DrawioCanvasToolNames.NO_SAFE_CANDIDATE);
            response.setMessage(rejection);
            response.setRepairBrief("NO_SAFE_CANDIDATE: " + rejection);
            return response;
        }
        response.setType(DrawioCanvasToolNames.APPLY_VISUAL_REPAIR);
        response.setMessage("Visual repair operations accepted for server-side application.");
        return response;
    }

    static String validateRequestShape(ApplyVisualRepairRequest request) {
        if (request == null || request.getRepairs() == null || request.getRepairs().isEmpty()) {
            return "repairs must contain at least one visual repair operation";
        }
        if (request.getRepairs().size() > MAX_OPERATIONS) {
            return "repairs may contain at most " + MAX_OPERATIONS + " operations";
        }
        for (int index = 0; index < request.getRepairs().size(); index++) {
            VisualRepairOperation operation = request.getRepairs().get(index);
            if (operation == null || operation.getAction() == null) {
                return "repair operation " + index + " requires an action";
            }
            List<String> targets = operation.getTargetCellIds() == null
                    ? List.of()
                    : operation.getTargetCellIds().stream().filter(StringUtils::isNotBlank).distinct().toList();
            int requiredTargets = switch (operation.getAction()) {
                case ALIGN_CELLS -> 2;
                case DISTRIBUTE_CELLS -> 3;
                default -> 1;
            };
            if (targets.size() < requiredTargets) {
                return operation.getAction() + " requires at least " + requiredTargets + " target cell ids";
            }
            if (operation.getAction() != VisualRepairAction.ALIGN_CELLS
                    && operation.getAction() != VisualRepairAction.DISTRIBUTE_CELLS
                    && targets.size() != 1) {
                return operation.getAction() + " requires exactly one target cell id";
            }
            if (operation.getAction() == VisualRepairAction.SET_GEOMETRY
                    && (operation.getGeometry() == null || operation.getGeometry().isEmpty())) {
                return "SET_GEOMETRY requires at least one geometry value";
            }
            if (operation.getAction() == VisualRepairAction.SET_STYLE
                    && (operation.getStyleUpdates() == null || operation.getStyleUpdates().isEmpty())) {
                return "SET_STYLE requires at least one style update";
            }
            if (operation.getAction() == VisualRepairAction.RECONNECT_EDGE
                    && StringUtils.isAllBlank(operation.getSourceCellId(), operation.getTargetCellId())) {
                return "RECONNECT_EDGE requires a sourceCellId or targetCellId";
            }
            if (operation.getAction() == VisualRepairAction.ALIGN_CELLS
                    && operation.getAlignment() == null) {
                return "ALIGN_CELLS requires alignment";
            }
            if (operation.getAction() == VisualRepairAction.DISTRIBUTE_CELLS
                    && operation.getAxis() == null) {
                return "DISTRIBUTE_CELLS requires axis";
            }
        }
        return "";
    }

    public enum VisualRepairAction {
        SET_GEOMETRY,
        SET_STYLE,
        REROUTE_EDGE,
        RECONNECT_EDGE,
        ALIGN_CELLS,
        DISTRIBUTE_CELLS
    }

    public enum VisualAlignment {
        LEFT,
        CENTER_X,
        RIGHT,
        TOP,
        CENTER_Y,
        BOTTOM
    }

    public enum VisualAxis {
        HORIZONTAL,
        VERTICAL
    }

    public enum VisualRouting {
        AUTO,
        ORTHOGONAL
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ApplyVisualRepairRequest {
        @JsonProperty(required = true, value = "repairs")
        @JsonPropertyDescription("One to three local repair operations applied atomically in list order.")
        private List<VisualRepairOperation> repairs;

        @JsonProperty(value = "reason")
        @JsonPropertyDescription("Short internal reason that summarizes the bounded visual repair.")
        private String reason;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VisualRepairOperation {
        @JsonProperty(required = true, value = "action")
        @JsonPropertyDescription("The single visual operation to apply.")
        private VisualRepairAction action;

        @JsonProperty(required = true, value = "targetCellIds")
        @JsonPropertyDescription("Grounded existing mxCell ids. Use one id except for ALIGN_CELLS and DISTRIBUTE_CELLS.")
        private List<String> targetCellIds;

        @JsonProperty(value = "geometry")
        @JsonPropertyDescription("Coordinates or dimensions for SET_GEOMETRY; omitted values remain unchanged.")
        private VisualRepairGeometry geometry;

        @JsonProperty(value = "styleUpdates")
        @JsonPropertyDescription("Whitelisted key/value presentation updates for SET_STYLE.")
        private List<VisualStyleUpdate> styleUpdates;

        @JsonProperty(value = "sourceCellId")
        @JsonPropertyDescription("Existing source node used only when the grounded edge has no source.")
        private String sourceCellId;

        @JsonProperty(value = "targetCellId")
        @JsonPropertyDescription("Existing target node used only when the grounded edge has no target.")
        private String targetCellId;

        @JsonProperty(value = "alignment")
        @JsonPropertyDescription("Alignment for ALIGN_CELLS. The first target cell is the anchor.")
        private VisualAlignment alignment;

        @JsonProperty(value = "axis")
        @JsonPropertyDescription("Distribution axis for DISTRIBUTE_CELLS.")
        private VisualAxis axis;

        @JsonProperty(value = "routing")
        @JsonPropertyDescription("AUTO preserves deliberate straight edges; ORTHOGONAL requests deterministic orthogonal routing.")
        private VisualRouting routing;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VisualRepairGeometry {
        private Double x;
        private Double y;
        private Double width;
        private Double height;

        boolean isEmpty() {
            return x == null && y == null && width == null && height == null;
        }
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class VisualStyleUpdate {
        @JsonProperty(required = true, value = "property")
        @JsonPropertyDescription("Whitelisted visual style property such as fillColor, strokeColor, fontColor, or fontSize.")
        private String property;

        @JsonProperty(required = true, value = "value")
        @JsonPropertyDescription("Draw.io style value without semicolons or line breaks.")
        private String value;
    }

}
