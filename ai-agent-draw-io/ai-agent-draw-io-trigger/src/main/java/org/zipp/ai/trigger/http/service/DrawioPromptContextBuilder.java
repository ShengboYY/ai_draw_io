package org.zipp.ai.trigger.http.service;

import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.IDrawioCanvasSnapshotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
@Slf4j
public class DrawioPromptContextBuilder {

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
        String contextType = contextType(taskType);
        // create_new starts from a blank slate; every other task hands the drawer the full current XML
        // so it can choose the concrete modify_diagram mode itself.
        String canvasContext = "create_new".equals(contextType)
                ? buildCreateNewContext(canvasXml)
                : buildFullXmlContext(requestDTO, canvasXml);
        // Keep this shape-only so logs stay useful without copying the Draw.io XML or prompt body.
        log.info("[draw-context] taskType={} contextType={} hasCanvas={} canvasXmlChars={} canvasSummaryChars={} userMessageChars={} contextChars={}",
                logValue(taskType),
                logValue(contextType),
                hasDrawableCanvas(canvasXml),
                textLength(canvasXml),
                textLength(null == requestDTO ? "" : requestDTO.getCanvasSummary()),
                textLength(rawUserMessage(requestDTO)),
                textLength(canvasContext));

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

    private String buildFullXmlContext(ChatRequestDTO requestDTO, String canvasXml) {
        String canvasSummary = resolveCanvasSummary(requestDTO, canvasXml);
        return "[Context: Current Draw.io XML]\n"
                + "```xml\n"
                + StringUtils.defaultString(canvasXml)
                + "\n```\n\n[Canvas Summary]\n"
                + canvasSummary;
    }

    private String taskType(IntentRoutingResult routingResult) {
        if (null == routingResult || StringUtils.isBlank(routingResult.getTaskType())) {
            return "edit_existing";
        }
        return routingResult.getTaskType();
    }

    private String contextType(String taskType) {
        return "create_new".equals(taskType) ? "create_new" : "full_xml";
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

    private int textLength(String value) {
        return null == value ? 0 : value.length();
    }

    private String logValue(String value) {
        if (null == value) {
            return "";
        }
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 160) + "...";
    }

}
