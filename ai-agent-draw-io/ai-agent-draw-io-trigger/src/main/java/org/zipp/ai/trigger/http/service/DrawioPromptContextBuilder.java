package org.zipp.ai.trigger.http.service;

import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.api.dto.DiagramConversationMessageDTO;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisIssue;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.service.IDrawioCanvasSnapshotService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service
@Slf4j
public class DrawioPromptContextBuilder {

    private static final int MAX_CANVAS_ISSUES = 8;
    private static final int MAX_CONVERSATION_CONTEXT_MESSAGES = 6;
    private static final int MAX_CONVERSATION_CONTEXT_CHARS = 800;
    private static final java.util.regex.Pattern MXGRAPH_PATTERN =
            java.util.regex.Pattern.compile("<mxGraphModel[\\s\\S]*?</mxGraphModel>");

    @Resource
    private IDrawioCanvasSnapshotService canvasSnapshotService;

    private final DrawioCanvasXmlToolkit canvasXmlToolkit = new DrawioCanvasXmlToolkit();

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
                + "\n\n"
                + buildConversationContext(requestDTO)
                + "\n\n[Canvas State]\n"
                + "hasCanvas=" + hasDrawableCanvas(canvasXml)
                + "\n\n[Canvas Summary]\n"
                + canvasSummary;
    }

    public String buildDrawingContextMessage(ChatRequestDTO requestDTO, IntentRoutingResult routingResult) {
        String canvasXml = resolveCanvasXml(requestDTO);
        String routeType = routeType(routingResult);
        String contextType = contextType(routeType);
        // create_new starts from a blank slate; every other task hands the drawer the full current XML
        // so it can choose the concrete modify_diagram mode itself.
        String canvasContext = "create_new".equals(contextType)
                ? buildCreateNewContext(canvasXml)
                : buildFullXmlContext(requestDTO, canvasXml);
        // Keep this shape-only so logs stay useful without copying the Draw.io XML or prompt body.
        log.info("[draw-context] routeType={} contextType={} hasCanvas={} canvasXmlChars={} canvasSummaryChars={} userMessageChars={} contextChars={}",
                logValue(routeType),
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
                    + "Existing canvas omitted because routeType=create_new.";
        }
        return "[Canvas Context]\n"
                + "No existing drawable canvas was provided.";
    }

    private String buildFullXmlContext(ChatRequestDTO requestDTO, String canvasXml) {
        String canvasSummary = resolveCanvasSummary(requestDTO, canvasXml);
        return buildCanvasStateContext(requestDTO, canvasXml)
                + "\n\n[Context: Current Draw.io XML]\n"
                + "```xml\n"
                + StringUtils.defaultString(canvasXml)
                + "\n```\n\n[Canvas Summary]\n"
                + canvasSummary
                + "\n\n"
                + buildCanvasIssuesContext(canvasXml);
    }

    private String buildCanvasStateContext(ChatRequestDTO requestDTO, String canvasXml) {
        Long expectedVersion = null == requestDTO ? null : requestDTO.getExpectedVersion();
        return "[Canvas State]\n"
                + "hasCanvas=" + hasDrawableCanvas(canvasXml)
                + "\nworkspaceIdPresent=" + StringUtils.isNotBlank(null == requestDTO ? "" : requestDTO.getUserId())
                + "\ndiagramId=" + StringUtils.defaultString(null == requestDTO ? "" : requestDTO.getDiagramId())
                + "\nexpectedVersion=" + (expectedVersion == null ? "" : expectedVersion);
    }

    private String buildCanvasIssuesContext(String canvasXml) {
        if (StringUtils.isBlank(canvasXml)) {
            return "[Canvas Issues]\nNo drawable Draw.io XML was provided.";
        }

        CanvasAnalysis analysis = canvasXmlToolkit.analyze(canvasXml);
        StringBuilder builder = new StringBuilder("[Canvas Issues]\n")
                .append("valid=").append(analysis.isValid()).append('\n')
                .append("severity=").append(analysis.getSeverity()).append('\n')
                .append("summary=").append(StringUtils.defaultString(analysis.getSummary().getSummary())).append('\n');
        List<CanvasAnalysisIssue> issues = analysis.getIssues();
        if (issues.isEmpty()) {
            return builder.append("issues=none").toString();
        }

        int limit = Math.min(MAX_CANVAS_ISSUES, issues.size());
        for (int i = 0; i < limit; i++) {
            CanvasAnalysisIssue issue = issues.get(i);
            builder.append("- type=").append(issue.getType())
                    .append(" severity=").append(issue.getSeverity())
                    .append(" targets=").append(String.join(",", issue.getTargetCellIds()))
                    .append(" repairability=").append(issue.getRepairability())
                    .append(" message=").append(compactIssueMessage(issue.getMessage()))
                    .append('\n');
        }
        if (issues.size() > MAX_CANVAS_ISSUES) {
            builder.append("- truncatedIssueCount=").append(issues.size() - MAX_CANVAS_ISSUES);
        }
        return builder.toString().trim();
    }

    private String compactIssueMessage(String message) {
        return StringUtils.defaultString(message)
                .replaceAll("[\\r\\n\\t]+", " ")
                .trim();
    }

    private String routeType(IntentRoutingResult routingResult) {
        if (null == routingResult || StringUtils.isBlank(routingResult.getRouteType())) {
            return "edit_existing";
        }
        return routingResult.getRouteType();
    }

    private String contextType(String routeType) {
        return "create_new".equals(routeType) ? "create_new" : "full_xml";
    }

    private String rawUserMessage(ChatRequestDTO requestDTO) {
        return null == requestDTO ? "" : StringUtils.defaultString(requestDTO.getMessage());
    }

    private String buildConversationContext(ChatRequestDTO requestDTO) {
        if (requestDTO == null || requestDTO.getConversationMessages() == null
                || requestDTO.getConversationMessages().isEmpty()) {
            return "[Conversation Context]\nNo prior visible chat turns were provided.";
        }

        java.util.List<DiagramConversationMessageDTO> messages = requestDTO.getConversationMessages();
        int start = Math.max(0, messages.size() - MAX_CONVERSATION_CONTEXT_MESSAGES);
        StringBuilder builder = new StringBuilder("[Conversation Context]\n")
                .append("Use these prior visible turns only to resolve follow-up answers and missing slots. ")
                .append("If the previous assistant asked for a topic/domain/requirement and the current user ")
                .append("provides a short phrase, keep the previous diagram type and treat the phrase as the missing detail.\n");
        for (int i = start; i < messages.size(); i++) {
            DiagramConversationMessageDTO message = messages.get(i);
            if (message == null || StringUtils.isBlank(message.getContent())) {
                continue;
            }
            String role = "agent".equals(message.getRole()) ? "assistant" : "user";
            String content = compactConversationText(message.getContent());
            if (StringUtils.isBlank(content)) {
                continue;
            }
            builder.append(role).append(": ").append(content).append('\n');
        }
        return builder.toString().trim();
    }

    private String compactConversationText(String content) {
        // Strip pasted or leaked Draw.io XML before it can pollute intent routing.
        String compact = MXGRAPH_PATTERN.matcher(StringUtils.defaultString(content)).replaceAll(" ")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return compact.length() <= MAX_CONVERSATION_CONTEXT_CHARS
                ? compact
                : compact.substring(0, MAX_CONVERSATION_CONTEXT_CHARS) + "...";
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
