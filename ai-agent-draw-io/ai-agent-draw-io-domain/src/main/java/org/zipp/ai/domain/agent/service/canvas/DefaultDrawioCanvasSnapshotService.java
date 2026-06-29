package org.zipp.ai.domain.agent.service.canvas;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasBounds;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasEdge;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasNode;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasPoint;
import org.zipp.ai.domain.agent.model.valobj.canvas.DrawioCanvasSnapshot;
import org.zipp.ai.domain.agent.service.IDrawioCanvasSnapshotService;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class DefaultDrawioCanvasSnapshotService implements IDrawioCanvasSnapshotService {

    @Override
    public DrawioCanvasSnapshot fromMessage(String message, String diagramType) {
        String xml = extractDrawioXml(message);
        if (StringUtils.isBlank(xml)) {
            return DrawioCanvasSnapshot.empty(diagramType, "No drawable Draw.io XML was found in the current context.");
        }
        return fromXml(xml, diagramType);
    }

    @Override
    public DrawioCanvasSnapshot fromXml(String xml, String diagramType) {
        if (StringUtils.isBlank(xml)) {
            return DrawioCanvasSnapshot.empty(diagramType, "No drawable Draw.io XML was provided.");
        }

        try {
            Document document = DocumentHelper.parseText(xml);
            List<CanvasNode> nodes = new ArrayList<>();
            List<CanvasEdge> edges = new ArrayList<>();
            parseCellElement(document.getRootElement(), nodes, edges);
            markContainerNodes(nodes);
            resolveAbsoluteCoordinates(nodes);
            CanvasBounds bounds = resolveBounds(nodes);

            return DrawioCanvasSnapshot.builder()
                    .valid(true)
                    .errorMessage("")
                    .diagramType(StringUtils.defaultIfBlank(diagramType, "unknown"))
                    .rawXml(xml)
                    .nodes(nodes)
                    .edges(edges)
                    .bounds(bounds)
                    .summary(buildSummary(nodes, edges, bounds))
                    .build();
        } catch (Exception e) {
            return DrawioCanvasSnapshot.empty(diagramType, "The Draw.io XML could not be parsed: " + e.getMessage());
        }
    }

    private void parseCellElement(Element element, List<CanvasNode> nodes, List<CanvasEdge> edges) {
        if ("mxCell".equals(element.getName())) {
            Element geometry = element.element("mxGeometry");
            if ("1".equals(element.attributeValue("vertex")) && null != geometry) {
                nodes.add(CanvasNode.builder()
                        .id(value(element.attributeValue("id")))
                        .parentId(value(element.attributeValue("parent")))
                        .label(cleanLabel(element.attributeValue("value")))
                        .rawLabel(value(element.attributeValue("value")))
                        .style(value(element.attributeValue("style")))
                        .x(number(geometry.attributeValue("x")))
                        .y(number(geometry.attributeValue("y")))
                        .width(number(geometry.attributeValue("width")))
                        .height(number(geometry.attributeValue("height")))
                        .text(isTextStyle(element.attributeValue("style")))
                        .container(isContainerStyle(element.attributeValue("style")))
                        .build());
            } else if ("1".equals(element.attributeValue("edge"))) {
                edges.add(CanvasEdge.builder()
                        .id(value(element.attributeValue("id")))
                        .parentId(value(element.attributeValue("parent")))
                        .label(cleanLabel(element.attributeValue("value")))
                        .rawLabel(value(element.attributeValue("value")))
                        .style(value(element.attributeValue("style")))
                        .source(value(element.attributeValue("source")))
                        .target(value(element.attributeValue("target")))
                        .points(parsePoints(geometry))
                        .build());
            }
        }

        for (Object child : element.elements()) {
            parseCellElement((Element) child, nodes, edges);
        }
    }

    private List<CanvasPoint> parsePoints(Element geometry) {
        if (null == geometry) {
            return Collections.emptyList();
        }

        List<CanvasPoint> points = new ArrayList<>();
        for (Object child : geometry.elements()) {
            Element point = (Element) child;
            if (!"mxPoint".equals(point.getName())) {
                continue;
            }
            points.add(CanvasPoint.builder()
                    .x(number(point.attributeValue("x")))
                    .y(number(point.attributeValue("y")))
                    .build());
        }
        return points;
    }

    private void markContainerNodes(List<CanvasNode> nodes) {
        Set<String> parentIds = nodes.stream()
                .map(CanvasNode::getParentId)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(HashSet::new));
        for (CanvasNode node : nodes) {
            if (parentIds.contains(node.getId()) || isContainerStyle(node.getStyle())) {
                node.setContainer(true);
            }
        }
    }

    private void resolveAbsoluteCoordinates(List<CanvasNode> nodes) {
        java.util.Map<String, CanvasNode> nodeMap = nodes.stream()
                .collect(Collectors.toMap(CanvasNode::getId, node -> node, (left, right) -> left));
        Set<String> resolved = new HashSet<>();
        for (CanvasNode node : nodes) {
            resolveNodePosition(node, nodeMap, resolved, new HashSet<>());
        }
    }

    private void resolveNodePosition(CanvasNode node,
                                     java.util.Map<String, CanvasNode> nodeMap,
                                     Set<String> resolved,
                                     Set<String> resolving) {
        if (null == node || resolved.contains(node.getId()) || resolving.contains(node.getId())) {
            return;
        }
        resolving.add(node.getId());
        CanvasNode parent = nodeMap.get(node.getParentId());
        if (null != parent) {
            resolveNodePosition(parent, nodeMap, resolved, resolving);
            node.setX(parent.getX() + node.getX());
            node.setY(parent.getY() + node.getY());
        }
        resolving.remove(node.getId());
        resolved.add(node.getId());
    }

    private CanvasBounds resolveBounds(List<CanvasNode> nodes) {
        if (nodes.isEmpty()) {
            return CanvasBounds.builder().x(0D).y(0D).width(0D).height(0D).build();
        }

        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (CanvasNode node : nodes) {
            minX = Math.min(minX, node.getX());
            minY = Math.min(minY, node.getY());
            maxX = Math.max(maxX, node.maxX());
            maxY = Math.max(maxY, node.maxY());
        }

        return CanvasBounds.builder()
                .x(minX)
                .y(minY)
                .width(Math.max(0D, maxX - minX))
                .height(Math.max(0D, maxY - minY))
                .build();
    }

    private String buildSummary(List<CanvasNode> nodes, List<CanvasEdge> edges, CanvasBounds bounds) {
        if (nodes.isEmpty()) {
            return "The current canvas has no drawable nodes.";
        }

        String labels = nodes.stream()
                .map(CanvasNode::getLabel)
                .filter(StringUtils::isNotBlank)
                .limit(8)
                .collect(Collectors.joining(", "));
        return "The canvas contains " + nodes.size() + " nodes and " + edges.size() + " edges. "
                + "Bounds: x=" + round(bounds.getX()) + ", y=" + round(bounds.getY())
                + ", width=" + round(bounds.getWidth()) + ", height=" + round(bounds.getHeight()) + ". "
                + "Main labels: " + labels + ".";
    }

    private String extractDrawioXml(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        String normalized = text
                .replace("```xml", "")
                .replace("```", "")
                .replace("\\\"", "\"")
                .replace("\\n", "")
                .replace("\\/", "/");
        int xmlStart = normalized.indexOf("<mxGraphModel");
        int xmlEnd = normalized.lastIndexOf("</mxGraphModel>");
        if (xmlStart < 0 || xmlEnd < xmlStart) {
            return "";
        }
        return normalized.substring(xmlStart, xmlEnd + "</mxGraphModel>".length());
    }

    private String cleanLabel(String label) {
        if (null == label) {
            return "";
        }
        return label.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("\\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isTextStyle(String style) {
        if (StringUtils.isBlank(style)) {
            return false;
        }
        String normalized = style.toLowerCase(Locale.ROOT);
        return normalized.startsWith("text;") || normalized.contains("shape=text");
    }

    private boolean isContainerStyle(String style) {
        if (StringUtils.isBlank(style)) {
            return false;
        }
        String normalized = style.toLowerCase(Locale.ROOT);
        return normalized.contains("swimlane")
                || normalized.contains("container")
                || normalized.contains("shape=folder")
                || normalized.contains("group");
    }

    private double number(String raw) {
        if (StringUtils.isBlank(raw)) {
            return 0D;
        }
        try {
            return Double.parseDouble(raw);
        } catch (Exception ignored) {
            return 0D;
        }
    }

    private String value(String raw) {
        return null == raw ? "" : raw;
    }

    private long round(double value) {
        return Math.round(value);
    }

}
