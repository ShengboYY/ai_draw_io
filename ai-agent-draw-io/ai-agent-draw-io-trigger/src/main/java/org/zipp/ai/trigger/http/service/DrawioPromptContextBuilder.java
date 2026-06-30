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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DrawioPromptContextBuilder {

    private static final int MAX_COMPACT_NODES = 24;
    private static final int MAX_COMPACT_EDGES = 24;
    private static final int MAX_PATCH_TARGETS = 8;
    private static final int MAX_STYLE_CHARS = 160;
    private static final Pattern CN_RENAME_TARGET = Pattern.compile("把\\s*(.+?)\\s*(?:改成|改为|换成|替换为|变成|重命名为)");
    private static final Pattern EN_RENAME_TARGET = Pattern.compile("\\b(?:rename|change|update)\\s+(.+?)\\s+(?:to|into|as)\\b");
    private static final Pattern EN_REPLACE_TARGET = Pattern.compile("\\breplace\\s+(.+?)\\s+with\\b");

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
        String diagramType = StringUtils.defaultString(routingResult.getDiagramType(), "unknown");
        String targetCells = buildPatchTargetCells(rawUserMessage(requestDTO), canvasXml, diagramType);
        if (StringUtils.isNotBlank(targetCells)) {
            return "[Canvas Summary]\n"
                    + canvasSummary
                    + "\n\n[Patch Target Cells]\n"
                    + targetCells;
        }

        return "[Canvas Summary]\n"
                + canvasSummary
                + "\n\n[Compact Canvas Snapshot]\n"
                + "No patch target cells matched the request; compact snapshot follows.\n"
                + buildCompactCanvasSnapshot(canvasXml, diagramType);
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

    private String buildPatchTargetCells(String message, String canvasXml, String diagramType) {
        if (StringUtils.isBlank(canvasXml) || null == canvasSnapshotService) {
            return "";
        }

        DrawioCanvasSnapshot snapshot = canvasSnapshotService.fromXml(canvasXml, diagramType);
        if (null == snapshot || !snapshot.isValid()) {
            return "";
        }

        List<String> terms = patchTargetTerms(message);
        if (terms.isEmpty()) {
            return "";
        }

        Map<String, CanvasNode> nodeById = snapshot.getNodes().stream()
                .collect(Collectors.toMap(CanvasNode::getId, node -> node, (left, right) -> left));
        List<PatchTarget> targets = new ArrayList<>();
        for (CanvasNode node : snapshot.getNodes()) {
            int score = nodeScore(node, terms);
            if (score > 0) {
                targets.add(new PatchTarget(node.getId(), score, formatNode(node) + " matchScore=" + score));
            }
        }
        for (CanvasEdge edge : snapshot.getEdges()) {
            int score = edgeScore(edge, nodeById, terms);
            if (score > 0) {
                targets.add(new PatchTarget(edge.getId(), score, formatEdge(edge) + " matchScore=" + score));
            }
        }
        if (targets.isEmpty()) {
            return "";
        }

        List<PatchTarget> chosen = targets.stream()
                .sorted(Comparator.<PatchTarget>comparingInt(PatchTarget::score).reversed().thenComparing(PatchTarget::id))
                .limit(MAX_PATCH_TARGETS)
                .collect(Collectors.toList());

        StringBuilder builder = new StringBuilder();
        builder.append("matchTerms=").append(String.join(", ", terms));
        builder.append("\ncandidates=").append(chosen.size());
        for (PatchTarget target : chosen) {
            builder.append("\n").append(target.line());
        }
        if (targets.size() > MAX_PATCH_TARGETS) {
            builder.append("\ncandidates_truncated=").append(targets.size() - MAX_PATCH_TARGETS);
        }
        return builder.toString();
    }

    private List<String> patchTargetTerms(String message) {
        String extracted = extractRenameTarget(message);
        String source = StringUtils.defaultIfBlank(extracted, message);
        Set<String> terms = new LinkedHashSet<>();
        String normalizedSource = normalizeForMatch(source);
        if (isUsefulSearchTerm(normalizedSource)) {
            terms.add(normalizedSource);
        }
        for (String token : normalizedSource.split("\\s+")) {
            if (isUsefulSearchTerm(token)) {
                terms.add(token);
            }
        }
        return new ArrayList<>(terms);
    }

    private String extractRenameTarget(String message) {
        String text = StringUtils.defaultString(message);
        for (Pattern pattern : List.of(CN_RENAME_TARGET, EN_RENAME_TARGET, EN_REPLACE_TARGET)) {
            Matcher matcher = pattern.matcher(text.toLowerCase(Locale.ROOT));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private int nodeScore(CanvasNode node, List<String> terms) {
        int score = 0;
        for (String term : terms) {
            score += fieldScore(node.getLabel(), term, 10);
            score += fieldScore(node.getRawLabel(), term, 8);
            score += fieldScore(node.getId(), term, 4);
        }
        return score;
    }

    private int edgeScore(CanvasEdge edge, Map<String, CanvasNode> nodeById, List<String> terms) {
        int score = 0;
        for (String term : terms) {
            score += fieldScore(edge.getLabel(), term, 10);
            score += fieldScore(edge.getRawLabel(), term, 8);
            score += fieldScore(edge.getId(), term, 4);
        }

        CanvasNode source = nodeById.get(edge.getSource());
        CanvasNode target = nodeById.get(edge.getTarget());
        boolean sourceMatched = endpointMatches(edge.getSource(), source, terms);
        boolean targetMatched = endpointMatches(edge.getTarget(), target, terms);
        if (sourceMatched && targetMatched) {
            // Endpoint-only edge matching is useful for requests like "change the API -> Gateway edge",
            // but a single endpoint mention usually means the node itself is the target.
            score += 8;
        }
        return score;
    }

    private boolean endpointMatches(String endpointId, CanvasNode endpointNode, List<String> terms) {
        for (String term : terms) {
            if (matches(endpointId, term)
                    || (endpointNode != null && (matches(endpointNode.getLabel(), term) || matches(endpointNode.getRawLabel(), term)))) {
                return true;
            }
        }
        return false;
    }

    private int fieldScore(String value, String term, int baseScore) {
        String normalized = normalizeForMatch(value);
        if (StringUtils.isBlank(normalized) || StringUtils.isBlank(term)) {
            return 0;
        }
        if (normalized.equals(term)) {
            return baseScore * 2;
        }
        if (normalized.contains(term) || term.contains(normalized)) {
            return baseScore;
        }
        return 0;
    }

    private boolean matches(String value, String term) {
        return fieldScore(value, term, 1) > 0;
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

    private String normalizeForMatch(String value) {
        return StringUtils.defaultString(value)
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}，。！？；：、（）【】《》“”‘’]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isUsefulSearchTerm(String term) {
        if (StringUtils.isBlank(term)) {
            return false;
        }
        String normalized = normalizeForMatch(term);
        if (StringUtils.isBlank(normalized)) {
            return false;
        }
        if (normalized.length() == 1 && !containsCjk(normalized)) {
            return false;
        }
        return !List.of(
                "把", "将", "给", "的", "节点", "颜色", "标签", "文字", "改", "改成", "改为", "换成", "替换为",
                "rename", "change", "update", "replace", "to", "into", "as", "with", "the", "a", "an", "of", "for",
                "label", "text", "color", "node"
        ).contains(normalized);
    }

    private boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(value.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
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

    private record PatchTarget(String id, int score, String line) {
    }

}
