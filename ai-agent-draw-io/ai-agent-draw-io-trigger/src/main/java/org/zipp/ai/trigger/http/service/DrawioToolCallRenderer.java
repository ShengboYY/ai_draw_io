package org.zipp.ai.trigger.http.service;

import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
public class DrawioToolCallRenderer {

    private static final Set<String> DRAWING_TOOL_TYPES = Set.of(
            "display_diagram",
            "append_diagram",
            "edit_diagram",
            "optimize_diagram",
            "update_cells",
            "route_edges",
            "continue_diagram"
    );

    // Localized edits can be merged into the live canvas instead of forcing a full iframe reload.
    private static final Set<String> LOCAL_EDIT_TOOL_TYPES = Set.of(
            "edit_diagram",
            "update_cells",
            "route_edges"
    );

    private final DrawioCanvasXmlToolkit xmlToolkit = new DrawioCanvasXmlToolkit();

    public boolean supports(String type) {
        return DRAWING_TOOL_TYPES.contains(type);
    }

    public List<JSONObject> render(JSONObject toolCall) {
        if (toolCall == null || !supports(toolCall.getString("type"))) {
            return List.of();
        }

        String xml = firstNonBlank(
                toolCall.getString("xml"),
                toolCall.getString("content"),
                toolCall.getString("updatedXml")
        );
        if (StringUtils.isBlank(xml)) {
            return List.of();
        }

        String graphModel = toGraphModel(xml);
        List<String> cells = extractRootCells(graphModel);

        List<JSONObject> chunks = new ArrayList<>();
        chunks.add(chunk("drawio_preview", "content", buildPreviewSkeleton(cells)));
        for (String cell : cells) {
            if (isVertex(cell) && !isSkeletonCell(cell)) {
                JSONObject node = chunk("drawio_node", "xml", cell);
                node.put("id", extractAttribute(cell, "id"));
                node.put("label", extractAttribute(cell, "value"));
                chunks.add(node);
            }
        }
        for (String cell : cells) {
            if (isEdge(cell)) {
                JSONObject edge = chunk("drawio_edge", "xml", cell);
                edge.put("id", extractAttribute(cell, "id"));
                edge.put("source", extractAttribute(cell, "source"));
                edge.put("target", extractAttribute(cell, "target"));
                edge.put("label", extractAttribute(cell, "value"));
                chunks.add(edge);
            }
        }
        chunks.add(validationChunk(graphModel));
        JSONObject done = chunk("drawio_done", "content", graphModel);
        // "local" lets the frontend merge into the existing canvas; "full" triggers a clean reload.
        done.put("mode", LOCAL_EDIT_TOOL_TYPES.contains(toolCall.getString("type")) ? "local" : "full");
        chunks.add(done);
        return chunks;
    }

    private JSONObject validationChunk(String graphModel) {
        DrawioCanvasXmlToolkit.CanvasInspection inspection = xmlToolkit.inspect(graphModel);
        JSONObject validation = chunk("validation_result", "content",
                inspection.isValid() ? "Diagram XML passed lightweight validation." : String.join("; ", inspection.getIssues()));
        validation.put("valid", inspection.isValid());
        validation.put("severity", inspection.getSeverity());
        validation.put("issues", inspection.getIssues());
        return validation;
    }

    private String toGraphModel(String xml) {
        String normalized = normalizeXml(xml);
        String graphModel = extractGraphModel(normalized);
        if (StringUtils.isNotBlank(graphModel)) {
            return graphModel;
        }

        return "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                + normalized
                + "</root></mxGraphModel>";
    }

    private String normalizeXml(String xml) {
        return StringUtils.trimToEmpty(xml)
                .replace("```xml", "")
                .replace("```", "")
                .replace("\\\"", "\"")
                .replace("\\n", "")
                .replace("\\/", "/")
                .trim();
    }

    private String extractGraphModel(String xml) {
        int xmlStart = xml.indexOf("<mxGraphModel");
        int xmlEnd = xml.lastIndexOf("</mxGraphModel>");
        if (xmlStart < 0 || xmlEnd < xmlStart) {
            return "";
        }
        return xml.substring(xmlStart, xmlEnd + "</mxGraphModel>".length());
    }

    private List<String> extractRootCells(String graphModel) {
        String rootContent = extractRootContent(graphModel);
        if (StringUtils.isBlank(rootContent)) {
            return List.of();
        }

        List<String> cells = new ArrayList<>();
        int cursor = 0;
        while (cursor < rootContent.length()) {
            int start = rootContent.indexOf("<mxCell", cursor);
            if (start < 0) {
                break;
            }

            int startTagEnd = rootContent.indexOf(">", start);
            if (startTagEnd < 0) {
                break;
            }

            boolean selfClosing = startTagEnd > start && rootContent.charAt(startTagEnd - 1) == '/';
            int end = selfClosing ? startTagEnd + 1 : rootContent.indexOf("</mxCell>", startTagEnd);
            if (end < 0) {
                break;
            }
            if (!selfClosing) {
                end += "</mxCell>".length();
            }

            String cell = rootContent.substring(start, end);
            String id = extractAttribute(cell, "id");
            if (!"0".equals(id) && !"1".equals(id)) {
                cells.add(cell);
            }
            cursor = end;
        }
        return cells;
    }

    private String extractRootContent(String graphModel) {
        int rootStart = graphModel.indexOf("<root>");
        int rootEnd = graphModel.lastIndexOf("</root>");
        if (rootStart < 0 || rootEnd < rootStart) {
            return "";
        }
        return graphModel.substring(rootStart + "<root>".length(), rootEnd);
    }

    private String buildPreviewSkeleton(List<String> cells) {
        StringBuilder builder = new StringBuilder();
        builder.append("<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>");
        for (String cell : cells) {
            if (isSkeletonCell(cell)) {
                builder.append(cell);
            }
        }
        builder.append("</root></mxGraphModel>");
        return builder.toString();
    }

    private boolean isSkeletonCell(String cell) {
        return isVertex(cell) && (cell.contains("container=1") || cell.contains("swimlane") || cell.contains("verticalAlign=top"));
    }

    private boolean isVertex(String cell) {
        return cell.contains("vertex=\"1\"") || cell.contains("vertex='1'");
    }

    private boolean isEdge(String cell) {
        return cell.contains("edge=\"1\"") || cell.contains("edge='1'");
    }

    private String extractAttribute(String cell, String attribute) {
        String doubleQuoted = attribute + "=\"";
        int doubleStart = cell.indexOf(doubleQuoted);
        if (doubleStart >= 0) {
            int valueStart = doubleStart + doubleQuoted.length();
            int valueEnd = cell.indexOf("\"", valueStart);
            return valueEnd > valueStart ? cell.substring(valueStart, valueEnd) : "";
        }

        String singleQuoted = attribute + "='";
        int singleStart = cell.indexOf(singleQuoted);
        if (singleStart >= 0) {
            int valueStart = singleStart + singleQuoted.length();
            int valueEnd = cell.indexOf("'", valueStart);
            return valueEnd > valueStart ? cell.substring(valueStart, valueEnd) : "";
        }
        return "";
    }

    private JSONObject chunk(String type, String key, String value) {
        JSONObject chunk = new JSONObject();
        chunk.put("type", type);
        chunk.put(key, value);
        return chunk;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }
}
