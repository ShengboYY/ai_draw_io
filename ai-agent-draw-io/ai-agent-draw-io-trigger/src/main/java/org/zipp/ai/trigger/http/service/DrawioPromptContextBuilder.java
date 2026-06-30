package org.zipp.ai.trigger.http.service;

import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasEdge;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasNode;
import org.zipp.ai.domain.agent.model.valobj.canvas.DrawioCanvasSnapshot;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.IDrawioCanvasSnapshotService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class DrawioPromptContextBuilder {

    private static final int MAX_COMPACT_NODES = 24;
    private static final int MAX_COMPACT_EDGES = 24;
    private static final int MAX_STYLE_CHARS = 160;

    @Resource
    private IDrawioCanvasSnapshotService canvasSnapshotService;

    public DrawioPromptContextBuilder() {
    }

    public DrawioPromptContextBuilder(IDrawioCanvasSnapshotService canvasSnapshotService) {
        this.canvasSnapshotService = canvasSnapshotService;
    }

    public String buildIntentMessage(ChatRequestDTO requestDTO) {
        String canvasXml = resolveCanvasXml(requestDTO);
        String canvasSummary = resolveCanvasSummary(requestDTO, canvasXml);
        // The router only needs lightweight canvas facts; full XML stays out to avoid intent pollution.
        return "[User Request]\n"
                + rawUserMessage(requestDTO)
                + "\n\n[Canvas State]\n"
                + "hasCanvas=" + hasDrawableCanvas(canvasXml)
                + "\n\n[Canvas Summary]\n"
                + canvasSummary;
    }

    public String buildDrawingContextMessage(ChatRequestDTO requestDTO, IntentRoutingResult routingResult) {
        String canvasXml = resolveCanvasXml(requestDTO);
        String taskType = taskType(routingResult);
        String canvasContext = switch (taskType) {
            case "create_new" -> buildCreateNewContext(canvasXml);
            case "patch_existing" -> buildPatchContext(requestDTO, routingResult, canvasXml);
            default -> buildFullXmlContext(requestDTO, canvasXml);
        };

        return canvasContext
                + "\n\n[User Request]\n"
                + rawUserMessage(requestDTO);
    }

    public String buildReviewContextMessage(ChatRequestDTO requestDTO, IntentRoutingResult routingResult) {
        String canvasXml = resolveCanvasXml(requestDTO);
        return buildFullXmlContext(requestDTO, canvasXml)
                + "\n\n[User Request]\n"
                + rawUserMessage(requestDTO);
    }

    public String resolveCanvasXml(ChatRequestDTO requestDTO) {
        if (null == requestDTO) {
            return "";
        }
        if (StringUtils.isNotBlank(requestDTO.getCanvasXml())) {
            return requestDTO.getCanvasXml();
        }
        return extractDrawioXml(requestDTO.getMessage());
    }

    public String resolveCanvasSummary(ChatRequestDTO requestDTO, String canvasXml) {
        if (null != requestDTO && StringUtils.isNotBlank(requestDTO.getCanvasSummary())) {
            return requestDTO.getCanvasSummary();
        }
        if (StringUtils.isBlank(canvasXml)) {
            return "No drawable Draw.io XML was found in the current context.";
        }
        if (null == canvasSnapshotService) {
            return hasDrawableCanvas(canvasXml)
                    ? "Canvas XML is available, but no compact summary was provided."
                    : "The current canvas has no drawable nodes.";
        }
        return canvasSnapshotService.fromXml(canvasXml, "unknown").getSummary();
    }

    private String buildCreateNewContext(String canvasXml) {
        if (hasDrawableCanvas(canvasXml)) {
            // A replacement diagram should not inherit labels or geometry from the old canvas.
            return "[Canvas Context]\n"
                    + "Existing canvas omitted because taskType=create_new.";
        }
        return "[Canvas Context]\n"
                + "No existing drawable canvas was provided.";
    }

    private String buildPatchContext(ChatRequestDTO requestDTO, IntentRoutingResult routingResult, String canvasXml) {
        String canvasSummary = resolveCanvasSummary(requestDTO, canvasXml);
        return "[Canvas Summary]\n"
                + canvasSummary
                + "\n\n[Compact Canvas Snapshot]\n"
                + buildCompactCanvasSnapshot(canvasXml, StringUtils.defaultString(routingResult.getDiagramType(), "unknown"));
    }

    private String buildFullXmlContext(ChatRequestDTO requestDTO, String canvasXml) {
        String canvasSummary = resolveCanvasSummary(requestDTO, canvasXml);
        return "[Context: Current Draw.io XML]\n"
                + "```xml\n"
                + StringUtils.defaultString(canvasXml)
                + "\n```\n\n[Canvas Summary]\n"
                + canvasSummary;
    }

    private String buildCompactCanvasSnapshot(String canvasXml, String diagramType) {
        if (StringUtils.isBlank(canvasXml)) {
            return "No drawable Draw.io XML was found in the current context.";
        }
        if (null == canvasSnapshotService) {
            return "Canvas XML is available, but no compact snapshot service was provided.";
        }

        DrawioCanvasSnapshot snapshot = canvasSnapshotService.fromXml(canvasXml, diagramType);
        if (null == snapshot || !snapshot.isValid()) {
            return null == snapshot ? "The Draw.io XML could not be parsed." : snapshot.getErrorMessage();
        }

        List<String> nodeLines = snapshot.getNodes().stream()
                .limit(MAX_COMPACT_NODES)
                .map(this::formatNode)
                .collect(Collectors.toList());
        List<String> edgeLines = snapshot.getEdges().stream()
                .limit(MAX_COMPACT_EDGES)
                .map(this::formatEdge)
                .collect(Collectors.toList());

        StringBuilder builder = new StringBuilder();
        builder.append("diagramType=").append(StringUtils.defaultIfBlank(snapshot.getDiagramType(), "unknown"));
        builder.append("\nnodes=").append(snapshot.nodeCount()).append(", edges=").append(snapshot.edgeCount());
        for (String line : nodeLines) {
            builder.append("\n").append(line);
        }
        if (snapshot.nodeCount() > MAX_COMPACT_NODES) {
            builder.append("\nnodes_truncated=").append(snapshot.nodeCount() - MAX_COMPACT_NODES);
        }
        for (String line : edgeLines) {
            builder.append("\n").append(line);
        }
        if (snapshot.edgeCount() > MAX_COMPACT_EDGES) {
            builder.append("\nedges_truncated=").append(snapshot.edgeCount() - MAX_COMPACT_EDGES);
        }
        return builder.toString();
    }

    private String formatNode(CanvasNode node) {
        return "node id=" + inline(node.getId())
                + " label=\"" + inline(node.getLabel()) + "\""
                + " x=" + number(node.getX())
                + " y=" + number(node.getY())
                + " w=" + number(node.getWidth())
                + " h=" + number(node.getHeight())
                + " style=\"" + inline(trimStyle(node.getStyle())) + "\"";
    }

    private String formatEdge(CanvasEdge edge) {
        return "edge id=" + inline(edge.getId())
                + " source=" + inline(edge.getSource())
                + " target=" + inline(edge.getTarget())
                + " label=\"" + inline(edge.getLabel()) + "\""
                + " style=\"" + inline(trimStyle(edge.getStyle())) + "\"";
    }

    private String taskType(IntentRoutingResult routingResult) {
        if (null == routingResult || StringUtils.isBlank(routingResult.getTaskType())) {
            return "fallback_full_xml";
        }
        return routingResult.getTaskType();
    }

    private String rawUserMessage(ChatRequestDTO requestDTO) {
        return null == requestDTO ? "" : StringUtils.defaultString(requestDTO.getMessage());
    }

    private boolean hasDrawableCanvas(String canvasXml) {
        return StringUtils.contains(canvasXml, "vertex=\"1\"")
                || StringUtils.contains(canvasXml, "vertex='1'")
                || StringUtils.contains(canvasXml, "edge=\"1\"")
                || StringUtils.contains(canvasXml, "edge='1'");
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

    private String trimStyle(String style) {
        return StringUtils.abbreviate(StringUtils.defaultString(style), MAX_STYLE_CHARS);
    }

    private String inline(String value) {
        return StringUtils.defaultString(value)
                .replace("\"", "'")
                .replace("\n", " ")
                .replace("\r", " ")
                .trim();
    }

    private String number(double value) {
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

}
