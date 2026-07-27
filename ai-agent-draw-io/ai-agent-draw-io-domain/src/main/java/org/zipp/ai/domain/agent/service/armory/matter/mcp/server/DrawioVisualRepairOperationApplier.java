package org.zipp.ai.domain.agent.service.armory.matter.mcp.server;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds one local candidate from a validated visual-repair operation batch. */
final class DrawioVisualRepairOperationApplier {

    private static final int MAX_TARGET_CELLS = 12;
    private static final double MAX_COORDINATE = 100_000D;
    private static final double MIN_SIZE = 20D;
    private static final double MAX_SIZE = 5_000D;
    private static final Set<String> ALLOWED_STYLE_PROPERTIES = Set.of(
            "fillColor", "strokeColor", "fontColor", "fontSize", "fontStyle",
            "strokeWidth", "opacity", "fillOpacity", "strokeOpacity", "rounded",
            "shadow", "whiteSpace", "html", "align", "verticalAlign", "spacing",
            "spacingTop", "spacingRight", "spacingBottom", "spacingLeft");

    private final DrawioCanvasXmlToolkit xmlToolkit = new DrawioCanvasXmlToolkit();

    ApplyResult apply(String currentXml, VisualRepairMcpService.ApplyVisualRepairRequest request) {
        String requestError = VisualRepairMcpService.validateRequestShape(request);
        if (StringUtils.isNotBlank(requestError)) {
            return ApplyResult.rejected(requestError);
        }
        if (StringUtils.isBlank(currentXml)) {
            return ApplyResult.rejected("current canvas is unavailable");
        }

        try {
            Document beforeDocument = DocumentHelper.parseText(xmlToolkit.toGraphModel(currentXml));
            Document workingDocument = DocumentHelper.parseText(beforeDocument.asXML());
            Element root = workingDocument.getRootElement().element("root");
            if (root == null) {
                return ApplyResult.rejected("current canvas has no Draw.io root");
            }

            Map<String, Element> cells = cellsById(root);
            LinkedHashSet<String> affectedIds = new LinkedHashSet<>();
            LinkedHashSet<String> rerouteEdgeIds = new LinkedHashSet<>();
            for (VisualRepairMcpService.VisualRepairOperation operation : request.getRepairs()) {
                List<String> targetIds = normalizedIds(operation.getTargetCellIds());
                affectedIds.addAll(targetIds);
                if (affectedIds.size() > MAX_TARGET_CELLS) {
                    return ApplyResult.rejected("repair batch may target at most " + MAX_TARGET_CELLS + " cells");
                }
                String rejection = applyOperation(
                        operation, targetIds, cells, rerouteEdgeIds);
                if (StringUtils.isNotBlank(rejection)) {
                    return ApplyResult.rejected(rejection);
                }
            }

            String candidate = workingDocument.asXML();
            if (!rerouteEdgeIds.isEmpty()) {
                candidate = xmlToolkit.routeEdges(candidate, Set.copyOf(rerouteEdgeIds));
            }
            Map<String, String> beforeCells = cellXmlById(beforeDocument, affectedIds);
            Map<String, String> afterCells = cellXmlById(
                    DocumentHelper.parseText(xmlToolkit.toGraphModel(candidate)), affectedIds);
            List<String> changed = affectedIds.stream()
                    .filter(id -> !StringUtils.equals(beforeCells.get(id), afterCells.get(id)))
                    .map(afterCells::get)
                    .filter(StringUtils::isNotBlank)
                    .toList();
            if (changed.isEmpty()) {
                return ApplyResult.rejected("repair operations produced no canvas change");
            }
            return ApplyResult.applied(candidate, String.join("", changed));
        } catch (Exception e) {
            return ApplyResult.rejected("visual repair could not be applied to the current canvas");
        }
    }

    private String applyOperation(VisualRepairMcpService.VisualRepairOperation operation,
                                  List<String> targetIds,
                                  Map<String, Element> cells,
                                  Set<String> rerouteEdgeIds) {
        List<Element> targets = new ArrayList<>();
        for (String id : targetIds) {
            Element cell = cells.get(id);
            if (cell == null) {
                return "unknown target cell id: " + id;
            }
            targets.add(cell);
        }

        return switch (operation.getAction()) {
            case SET_GEOMETRY -> setGeometry(targets.get(0), operation.getGeometry());
            case SET_STYLE -> setStyle(targets.get(0), operation.getStyleUpdates());
            case REROUTE_EDGE -> rerouteEdge(targets.get(0), operation.getRouting(), rerouteEdgeIds);
            case RECONNECT_EDGE -> reconnectEdge(
                    targets.get(0), operation, cells, rerouteEdgeIds);
            case ALIGN_CELLS -> alignCells(targets, operation.getAlignment());
            case DISTRIBUTE_CELLS -> distributeCells(targets, operation.getAxis());
        };
    }

    private String setGeometry(Element cell, VisualRepairMcpService.VisualRepairGeometry geometry) {
        if (!isVertex(cell)) {
            return "SET_GEOMETRY target must be a vertex";
        }
        if (!validCoordinate(geometry.getX()) || !validCoordinate(geometry.getY())
                || !validSize(geometry.getWidth()) || !validSize(geometry.getHeight())) {
            return "SET_GEOMETRY values are outside the safe canvas bounds";
        }
        Element mxGeometry = geometry(cell);
        setNumber(mxGeometry, "x", geometry.getX());
        setNumber(mxGeometry, "y", geometry.getY());
        setNumber(mxGeometry, "width", geometry.getWidth());
        setNumber(mxGeometry, "height", geometry.getHeight());
        return "";
    }

    private String setStyle(Element cell, List<VisualRepairMcpService.VisualStyleUpdate> updates) {
        String style = StringUtils.defaultString(cell.attributeValue("style"));
        for (VisualRepairMcpService.VisualStyleUpdate update : updates) {
            String property = update == null ? "" : StringUtils.trimToEmpty(update.getProperty());
            String value = update == null ? "" : StringUtils.trimToEmpty(update.getValue());
            if (!ALLOWED_STYLE_PROPERTIES.contains(property)) {
                return "SET_STYLE property is not allowed: " + property;
            }
            if (StringUtils.isBlank(value) || value.length() > 64
                    || value.indexOf(';') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                return "SET_STYLE contains an invalid value for " + property;
            }
            style = withStyleProperty(style, property, value);
        }
        cell.addAttribute("style", style);
        return "";
    }

    private String rerouteEdge(Element edge,
                               VisualRepairMcpService.VisualRouting routing,
                               Set<String> rerouteEdgeIds) {
        if (!isEdge(edge)) {
            return "REROUTE_EDGE target must be an edge";
        }
        if (StringUtils.isAnyBlank(edge.attributeValue("source"), edge.attributeValue("target"))) {
            return "REROUTE_EDGE target must have existing source and target nodes";
        }
        if (routing == VisualRepairMcpService.VisualRouting.ORTHOGONAL) {
            edge.addAttribute("style", withStyleProperty(
                    StringUtils.defaultString(edge.attributeValue("style")),
                    "edgeStyle", "orthogonalEdgeStyle"));
        }
        rerouteEdgeIds.add(edge.attributeValue("id"));
        return "";
    }

    private String reconnectEdge(Element edge,
                                 VisualRepairMcpService.VisualRepairOperation operation,
                                 Map<String, Element> cells,
                                 Set<String> rerouteEdgeIds) {
        if (!isEdge(edge)) {
            return "RECONNECT_EDGE target must be an edge";
        }
        if (StringUtils.isNotBlank(edge.attributeValue("source"))
                && StringUtils.isNotBlank(operation.getSourceCellId())
                && !StringUtils.equals(edge.attributeValue("source"), operation.getSourceCellId())) {
            return "RECONNECT_EDGE cannot replace an existing source endpoint";
        }
        if (StringUtils.isNotBlank(edge.attributeValue("target"))
                && StringUtils.isNotBlank(operation.getTargetCellId())
                && !StringUtils.equals(edge.attributeValue("target"), operation.getTargetCellId())) {
            return "RECONNECT_EDGE cannot replace an existing target endpoint";
        }
        String sourceId = StringUtils.defaultIfBlank(
                operation.getSourceCellId(), edge.attributeValue("source"));
        String targetId = StringUtils.defaultIfBlank(
                operation.getTargetCellId(), edge.attributeValue("target"));
        if (!isVertex(cells.get(sourceId)) || !isVertex(cells.get(targetId))) {
            return "RECONNECT_EDGE endpoints must reference existing vertex cells";
        }
        edge.addAttribute("source", sourceId);
        edge.addAttribute("target", targetId);
        if (operation.getRouting() == VisualRepairMcpService.VisualRouting.ORTHOGONAL) {
            edge.addAttribute("style", withStyleProperty(
                    StringUtils.defaultString(edge.attributeValue("style")),
                    "edgeStyle", "orthogonalEdgeStyle"));
        }
        rerouteEdgeIds.add(edge.attributeValue("id"));
        return "";
    }

    private String alignCells(List<Element> cells, VisualRepairMcpService.VisualAlignment alignment) {
        if (cells.stream().anyMatch(cell -> !isVertex(cell))) {
            return "ALIGN_CELLS targets must all be vertices";
        }
        Element anchorGeometry = geometry(cells.get(0));
        double anchorX = number(anchorGeometry, "x");
        double anchorY = number(anchorGeometry, "y");
        double anchorWidth = number(anchorGeometry, "width");
        double anchorHeight = number(anchorGeometry, "height");
        for (Element cell : cells) {
            Element current = geometry(cell);
            double width = number(current, "width");
            double height = number(current, "height");
            switch (alignment) {
                case LEFT -> setNumber(current, "x", anchorX);
                case CENTER_X -> setNumber(current, "x", anchorX + (anchorWidth - width) / 2D);
                case RIGHT -> setNumber(current, "x", anchorX + anchorWidth - width);
                case TOP -> setNumber(current, "y", anchorY);
                case CENTER_Y -> setNumber(current, "y", anchorY + (anchorHeight - height) / 2D);
                case BOTTOM -> setNumber(current, "y", anchorY + anchorHeight - height);
            }
        }
        return "";
    }

    private String distributeCells(List<Element> cells, VisualRepairMcpService.VisualAxis axis) {
        if (cells.stream().anyMatch(cell -> !isVertex(cell))) {
            return "DISTRIBUTE_CELLS targets must all be vertices";
        }
        Comparator<Element> comparator = Comparator.comparingDouble(
                cell -> axis == VisualRepairMcpService.VisualAxis.HORIZONTAL
                        ? centerX(cell)
                        : centerY(cell));
        List<Element> ordered = cells.stream().sorted(comparator).toList();
        double first = axis == VisualRepairMcpService.VisualAxis.HORIZONTAL
                ? centerX(ordered.get(0))
                : centerY(ordered.get(0));
        double last = axis == VisualRepairMcpService.VisualAxis.HORIZONTAL
                ? centerX(ordered.get(ordered.size() - 1))
                : centerY(ordered.get(ordered.size() - 1));
        double step = (last - first) / (ordered.size() - 1);
        for (int index = 1; index < ordered.size() - 1; index++) {
            Element current = geometry(ordered.get(index));
            double center = first + step * index;
            if (axis == VisualRepairMcpService.VisualAxis.HORIZONTAL) {
                setNumber(current, "x", center - number(current, "width") / 2D);
            } else {
                setNumber(current, "y", center - number(current, "height") / 2D);
            }
        }
        return "";
    }

    private Map<String, Element> cellsById(Element root) {
        Map<String, Element> cells = new LinkedHashMap<>();
        for (Object item : root.elements("mxCell")) {
            Element cell = (Element) item;
            if (StringUtils.isNotBlank(cell.attributeValue("id"))) {
                cells.put(cell.attributeValue("id"), cell);
            }
        }
        return cells;
    }

    private Map<String, String> cellXmlById(Document document, Set<String> ids) {
        Element root = document.getRootElement().element("root");
        if (root == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (Object item : root.elements("mxCell")) {
            Element cell = (Element) item;
            if (ids.contains(cell.attributeValue("id"))) {
                result.put(cell.attributeValue("id"), cell.asXML());
            }
        }
        return result;
    }

    private List<String> normalizedIds(List<String> ids) {
        return ids.stream().map(String::trim).filter(StringUtils::isNotBlank).distinct().toList();
    }

    private Element geometry(Element cell) {
        Element geometry = cell.element("mxGeometry");
        if (geometry == null) {
            geometry = cell.addElement("mxGeometry");
            geometry.addAttribute("as", "geometry");
        }
        return geometry;
    }

    private boolean isVertex(Element cell) {
        return cell != null && "1".equals(cell.attributeValue("vertex"));
    }

    private boolean isEdge(Element cell) {
        return cell != null && "1".equals(cell.attributeValue("edge"));
    }

    private boolean validCoordinate(Double value) {
        return value == null || (Double.isFinite(value) && value >= 0D && value <= MAX_COORDINATE);
    }

    private boolean validSize(Double value) {
        return value == null || (Double.isFinite(value) && value >= MIN_SIZE && value <= MAX_SIZE);
    }

    private double centerX(Element cell) {
        Element geometry = geometry(cell);
        return number(geometry, "x") + number(geometry, "width") / 2D;
    }

    private double centerY(Element cell) {
        Element geometry = geometry(cell);
        return number(geometry, "y") + number(geometry, "height") / 2D;
    }

    private double number(Element element, String attribute) {
        try {
            return Double.parseDouble(StringUtils.defaultIfBlank(element.attributeValue(attribute), "0"));
        } catch (NumberFormatException ignored) {
            return 0D;
        }
    }

    private void setNumber(Element element, String attribute, Double value) {
        if (value != null) {
            element.addAttribute(attribute, BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
        }
    }

    private String withStyleProperty(String style, String property, String value) {
        List<String> tokens = new ArrayList<>();
        for (String token : StringUtils.defaultString(style).split(";")) {
            String normalized = token.trim();
            if (normalized.isEmpty()) continue;
            int separator = normalized.indexOf('=');
            String key = separator < 0 ? normalized : normalized.substring(0, separator);
            if (!key.equalsIgnoreCase(property)) {
                tokens.add(normalized);
            }
        }
        tokens.add(property + "=" + value);
        return String.join(";", tokens) + ";";
    }

    record ApplyResult(boolean applied, String candidateXml, String changedCells, String rejectionReason) {
        static ApplyResult applied(String candidateXml, String changedCells) {
            return new ApplyResult(true, candidateXml, changedCells, "");
        }

        static ApplyResult rejected(String reason) {
            return new ApplyResult(false, "", "", StringUtils.defaultString(reason));
        }
    }
}
