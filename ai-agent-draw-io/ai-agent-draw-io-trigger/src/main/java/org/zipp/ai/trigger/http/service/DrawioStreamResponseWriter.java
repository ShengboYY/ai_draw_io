package org.zipp.ai.trigger.http.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
public class DrawioStreamResponseWriter {

    private final DrawioToolCallRenderer toolCallRenderer;
    private final DrawioCanvasXmlToolkit xmlToolkit = new DrawioCanvasXmlToolkit();
    private final ConcurrentMap<String, StringBuilder> fallbackContinuationBuffers = new ConcurrentHashMap<>();
    // Invalid diagrams are held here so the review loop can repair them before final canvas emission.
    private final ConcurrentMap<ResponseBodyEmitter, PendingDiagram> pendingDiagrams = new ConcurrentHashMap<>();
    // Current canvas per in-flight stream, so a localized patch (only the changed cell fragment) can be
    // merged server-side without the model re-sending the whole diagram.
    private final ConcurrentMap<ResponseBodyEmitter, String> currentCanvasByEmitter = new ConcurrentHashMap<>();
    // Last localized merge emitted per stream; multiple author buffers can carry the same patch, so skip
    // re-rendering an identical result.
    private final ConcurrentMap<ResponseBodyEmitter, String> lastPatchByEmitter = new ConcurrentHashMap<>();

    public DrawioStreamResponseWriter(DrawioToolCallRenderer toolCallRenderer) {
        this.toolCallRenderer = toolCallRenderer;
    }

    public void sendDirectReply(ResponseBodyEmitter emitter, String content) throws Exception {
        sendUserChunk(emitter, "done", content);
        sendDone(emitter);
        emitter.complete();
    }

    /**
     * Process a single line: try to parse as Draw.io JSON, otherwise send as status text.
     */
    public boolean processAndSendLine(ResponseBodyEmitter emitter, String phase, String line) throws Exception {
        try {
            com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(line);
            if (json != null && json.containsKey("type")) {
                Boolean handled = dispatchTypedJson(emitter, phase, json);
                if (handled != null) {
                    return handled;
                }
            }

            if (processIntentJson(emitter, phase, json)) {
                return true;
            }
        } catch (Exception parseEx) {
            // Not a single JSON line; it may be several objects concatenated without newlines.
        }

        // Dispatch every embedded object by type (handles concatenated JSON like patch_cells + review_result).
        boolean handledAny = false;
        for (com.alibaba.fastjson.JSONObject embedded : extractAllJsonObjects(line)) {
            if (embedded.containsKey("type")) {
                Boolean handled = dispatchTypedJson(emitter, phase, embedded);
                if (Boolean.TRUE.equals(handled)) {
                    return true;
                }
                if (handled != null) {
                    handledAny = true;
                }
            } else if (processIntentJson(emitter, phase, embedded)) {
                return true;
            }
        }
        if (handledAny) {
            return false;
        }

        String extractedDrawioXml = extractDrawioXml(line);
        if (StringUtils.isNotBlank(extractedDrawioXml)) {
            sendDrawioStream(emitter, phase, extractedDrawioXml);
            return false;
        }

        sendStatus(emitter, phase, line);
        return false;
    }

    /**
     * Dispatch one typed JSON object emitted by the model. Returns null when the type is not recognized
     * (caller may treat it as intent/text), TRUE when handled and the stream should complete, FALSE when
     * handled and the stream should continue.
     */
    private Boolean dispatchTypedJson(ResponseBodyEmitter emitter, String phase, com.alibaba.fastjson.JSONObject json) throws Exception {
        String type = json.getString("type");
        if (DrawioCanvasToolNames.CONTINUE_DIAGRAM.equals(type) && json.containsKey("xmlFragment")) {
            sendFallbackContinuation(emitter, phase, json);
            return false;
        }

        // Localized patch: merge the changed fragment into the canvas we hold (text fallback,
        // since this model returns NDJSON instead of real tool calls).
        if (isPatchFallback(json)
                && sendLocalCellPatch(emitter, phase, currentCanvasByEmitter.get(emitter), json.getString("cells"))) {
            return false;
        }

        if (sendToolCallResult(emitter, phase, type, json)) {
            return false;
        }

        if (isForwardedChunkType(type)) {
            if ("drawio_done".equals(type)) {
                String doneContent = json.getString("content");
                // The model may emit only the changed cell(s) under drawio_done; merge those too.
                if (isCellFragmentOnly(doneContent)
                        && sendLocalCellPatch(emitter, phase, currentCanvasByEmitter.get(emitter), doneContent)) {
                    return false;
                }
                sendDrawioDone(emitter, phase, doneContent);
                return false;
            }
            com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
            wrapper.put("phase", phase);
            wrapper.put("chunk", json);
            emitter.send(wrapper.toJSONString() + "\n");

            if ("drawio_node".equals(type) || "drawio_edge".equals(type)) {
                Thread.sleep(250);
            }

            // Fatal parse failures cannot be repaired by the frontend; finish the stream cleanly.
            if (shouldCompleteAfterFatalValidation(type, json)) {
                return true;
            }

            return "user".equals(type);
        }

        if ("review_result".equals(type)) {
            com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
            wrapper.put("phase", phase);
            wrapper.put("chunk", json);
            emitter.send(wrapper.toJSONString() + "\n");
            return "reviewing".equals(phase) && json.getBooleanValue("approved");
        }

        return null;
    }

    public boolean supportsToolCall(String type) {
        return toolCallRenderer.supports(type);
    }

    // True when the content carries loose mxCell fragment(s) but no full mxGraphModel wrapper.
    private boolean isCellFragmentOnly(String content) {
        if (StringUtils.isBlank(content)) {
            return false;
        }
        return content.contains("<mxCell") && !content.contains("<mxGraphModel");
    }

    private boolean isPatchFallback(com.alibaba.fastjson.JSONObject json) {
        String type = json.getString("type");
        return DrawioCanvasToolNames.PATCH_CELLS.equals(type)
                || (DrawioCanvasToolNames.MODIFY_DIAGRAM.equals(type)
                && "patch".equals(json.getString("mode"))
                && StringUtils.isNotBlank(json.getString("cells")));
    }

    /**
     * Merge a model-supplied cell fragment into the canvas the backend already holds, so a localized
     * edit (rename/recolor/move) never requires the model to re-emit the whole diagram. Routed through
     * the update_cells renderer path, which tags the result mode=local for in-place frontend merge.
     */
    public boolean sendLocalCellPatch(ResponseBodyEmitter emitter, String phase, String currentCanvasXml, String cells) throws Exception {
        if (StringUtils.isBlank(cells) || StringUtils.isBlank(currentCanvasXml)) {
            return false;
        }
        String merged = xmlToolkit.replaceCells(currentCanvasXml, cells);
        if (StringUtils.isBlank(merged)) {
            return false;
        }
        if (merged.equals(lastPatchByEmitter.get(emitter))) {
            return true; // Already emitted this exact merge for the stream; treat as handled, don't resend.
        }
        lastPatchByEmitter.put(emitter, merged);
        com.alibaba.fastjson.JSONObject toolJson = new com.alibaba.fastjson.JSONObject();
        toolJson.put("type", DrawioCanvasToolNames.UPDATE_CELLS);
        toolJson.put("xml", merged);
        return processAndSendLine(emitter, phase, toolJson.toJSONString());
    }

    public String extractDrawioXml(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }

        String normalized = text
                .replace("```xml", "")
                .replace("```", "")
                .replace("\\\"", "\"")
                .replace("\\n", "")
                .replace("\\/", "/")
                .trim();

        int xmlStart = normalized.indexOf("<mxGraphModel");
        int xmlEnd = normalized.lastIndexOf("</mxGraphModel>");
        if (xmlStart < 0 || xmlEnd < xmlStart) {
            return "";
        }

        return normalized.substring(xmlStart, xmlEnd + "</mxGraphModel>".length());
    }

    public boolean isIncompleteDrawioXml(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }

        String normalized = text.replace("\\\"", "\"").replace("\\/", "/");
        return normalized.contains("<mxGraphModel") && !normalized.contains("</mxGraphModel>");
    }

    public void sendToken(ResponseBodyEmitter emitter, String phase, String content) {
        try {
            com.alibaba.fastjson.JSONObject tokenMsg = new com.alibaba.fastjson.JSONObject();
            tokenMsg.put("phase", phase);
            com.alibaba.fastjson.JSONObject tokenChunk = new com.alibaba.fastjson.JSONObject();
            tokenChunk.put("type", "token");
            tokenChunk.put("content", content);
            tokenMsg.put("chunk", tokenChunk);
            emitter.send(tokenMsg.toJSONString() + "\n");
        } catch (Exception ignored) {
        }
    }

    public void sendDrawioDone(ResponseBodyEmitter emitter, String phase, String xml) throws Exception {
        sendDrawioDone(emitter, phase, xml, true, null);
    }

    public void sendDrawioStream(ResponseBodyEmitter emitter, String phase, String xml) throws Exception {
        com.alibaba.fastjson.JSONObject toolCall = new com.alibaba.fastjson.JSONObject();
        toolCall.put("type", DrawioCanvasToolNames.CREATE_DIAGRAM);
        toolCall.put("xml", xml);
        sendToolCallResult(emitter, phase, DrawioCanvasToolNames.CREATE_DIAGRAM, toolCall);
    }

    public void flushPendingDiagram(ResponseBodyEmitter emitter, String phase) throws Exception {
        PendingDiagram pendingDiagram = pendingDiagrams.remove(emitter);
        if (pendingDiagram == null) {
            return;
        }

        if (isCriticalSeverity(pendingDiagram.severity)) {
            sendError(emitter, phase, "Diagram XML could not be finalized: " + pendingDiagram.content);
            return;
        }

        sendDrawioDoneUnchecked(emitter, phase, pendingDiagram.xml);
    }

    public void clearPendingDiagram(ResponseBodyEmitter emitter) {
        pendingDiagrams.remove(emitter);
        currentCanvasByEmitter.remove(emitter);
        lastPatchByEmitter.remove(emitter);
    }

    public void setCurrentCanvas(ResponseBodyEmitter emitter, String canvasXml) {
        if (StringUtils.isNotBlank(canvasXml)) {
            currentCanvasByEmitter.put(emitter, canvasXml);
        }
    }

    private void sendDrawioDone(ResponseBodyEmitter emitter, String phase, String xml, boolean includeValidation, String mode) throws Exception {
        DrawioCanvasXmlToolkit.CanvasInspection inspection = xmlToolkit.inspect(xml);
        if (includeValidation) {
            sendValidationChunk(emitter, phase, inspection);
        }

        // Visual warnings still need a canvas to inspect; only structural failures are held back.
        if (!inspection.isValid() && isCriticalSeverity(inspection.getSeverity())) {
            rememberPendingDiagram(emitter, xml, inspection);
            return;
        }

        pendingDiagrams.remove(emitter);
        sendDrawioDoneUnchecked(emitter, phase, xml, mode);
    }

    private void sendDrawioDoneUnchecked(ResponseBodyEmitter emitter, String phase, String xml) throws Exception {
        sendDrawioDoneUnchecked(emitter, phase, xml, null);
    }

    private void sendDrawioDoneUnchecked(ResponseBodyEmitter emitter, String phase, String xml, String mode) throws Exception {
        com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
        wrapper.put("phase", phase);
        com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
        chunk.put("type", "drawio_done");
        chunk.put("content", xml);
        // Absent/"full" => clean reload; "local" => merge into the live canvas without remounting.
        chunk.put("mode", StringUtils.isNotBlank(mode) ? mode : "full");
        wrapper.put("chunk", chunk);
        emitter.send(wrapper.toJSONString() + "\n");
    }

    public void sendDone(ResponseBodyEmitter emitter) throws Exception {
        com.alibaba.fastjson.JSONObject doneMsg = new com.alibaba.fastjson.JSONObject();
        doneMsg.put("phase", "done");
        com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
        chunk.put("type", "done");
        doneMsg.put("chunk", chunk);
        emitter.send(doneMsg.toJSONString() + "\n");
    }

    public String resolvePhase(String author) {
        if (StringUtils.isBlank(author)) {
            return "thinking";
        }

        String normalizedAuthor = author.toLowerCase();
        if (normalizedAuthor.contains("review") || normalizedAuthor.contains("critic") || normalizedAuthor.contains("check")) {
            return "reviewing";
        }
        if (normalizedAuthor.contains("revision") || normalizedAuthor.contains("revise") || normalizedAuthor.contains("planner")) {
            return "revising";
        }
        if (normalizedAuthor.contains("draw") || normalizedAuthor.contains("generator") || normalizedAuthor.contains("render")) {
            return "drawing";
        }
        if (normalizedAuthor.contains("intent") || normalizedAuthor.contains("analyst") || normalizedAuthor.contains("analysis") || normalizedAuthor.contains("analyze")) {
            return "analyzing";
        }

        return "thinking";
    }

    public void handleStreamError(ResponseBodyEmitter emitter, boolean manuallyCompleted, Throwable error) {
        clearPendingDiagram(emitter);
        if (manuallyCompleted) {
            return;
        }
        if (isCompletedEmitterError(error)) {
            log.warn("流式对话已结束(客户端断开或主动完成)");
            return;
        }
        if (isClientDisconnect(error)) {
            log.warn("流式对话连接断开: {}", null == error.getCause() ? error.getMessage() : error.getCause().getMessage());
            return;
        }
        log.error("流式对话异常", error);
        try {
            com.alibaba.fastjson.JSONObject errMsg = new com.alibaba.fastjson.JSONObject();
            errMsg.put("phase", "error");
            com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
            chunk.put("type", "error");
            chunk.put("content", "对话异常，请重试");
            errMsg.put("chunk", chunk);
            emitter.send(errMsg.toJSONString() + "\n");
        } catch (Exception ignored) {
        }
        emitter.completeWithError(error);
    }

    private boolean sendToolCallResult(ResponseBodyEmitter emitter, String phase, String type, com.alibaba.fastjson.JSONObject toolCall) throws Exception {
        if (!toolCallRenderer.supports(type)) {
            return false;
        }

        List<com.alibaba.fastjson.JSONObject> chunks = toolCallRenderer.render(toolCall);
        if (chunks.isEmpty()) {
            return false;
        }

        com.alibaba.fastjson.JSONObject validationChunk = firstChunkOfType(chunks, "validation_result");
        com.alibaba.fastjson.JSONObject doneChunk = firstChunkOfType(chunks, "drawio_done");
        if (doneChunk != null && shouldWithholdFinalDiagram(validationChunk)) {
            // Hold only structural XML failures; visual warnings should still stream to the canvas.
            sendWrappedChunk(emitter, phase, validationChunk);
            rememberPendingDiagram(emitter, doneChunk.getString("content"), validationChunk);
            return true;
        }

        pendingDiagrams.remove(emitter);
        for (com.alibaba.fastjson.JSONObject chunk : chunks) {
            if ("drawio_done".equals(chunk.getString("type"))) {
                sendDrawioDone(emitter, phase, chunk.getString("content"), false, chunk.getString("mode"));
                continue;
            }
            com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
            wrapper.put("phase", phase);
            wrapper.put("chunk", chunk);
            emitter.send(wrapper.toJSONString() + "\n");
            if ("drawio_node".equals(chunk.getString("type")) || "drawio_edge".equals(chunk.getString("type"))) {
                Thread.sleep(90);
            }
        }
        return true;
    }

    private void sendFallbackContinuation(ResponseBodyEmitter emitter,
                                          String phase,
                                          com.alibaba.fastjson.JSONObject json) throws Exception {
        String continuationId = StringUtils.defaultIfBlank(json.getString("continuationId"), "default");
        if (json.getBooleanValue("reset")) {
            fallbackContinuationBuffers.remove(continuationId);
        }

        StringBuilder buffer = fallbackContinuationBuffers.computeIfAbsent(continuationId, ignored -> new StringBuilder());
        buffer.append(StringUtils.defaultString(json.getString("xmlFragment")));

        if (!json.getBooleanValue("done")) {
            com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
            chunk.put("type", "continuation_result");
            chunk.put("continuationId", continuationId);
            chunk.put("complete", false);
            chunk.put("bufferedLength", buffer.length());
            chunk.put("message", "Buffered XML fragment.");
            sendWrappedChunk(emitter, phase, chunk);
            return;
        }

        com.alibaba.fastjson.JSONObject toolCall = new com.alibaba.fastjson.JSONObject();
        toolCall.put("type", DrawioCanvasToolNames.CONTINUE_DIAGRAM);
        toolCall.put("xml", buffer.toString());
        fallbackContinuationBuffers.remove(continuationId);
        sendToolCallResult(emitter, phase, DrawioCanvasToolNames.CONTINUE_DIAGRAM, toolCall);
    }

    private void sendValidationChunk(ResponseBodyEmitter emitter, String phase, String xml) throws Exception {
        if (StringUtils.isBlank(xml)) {
            return;
        }
        DrawioCanvasXmlToolkit.CanvasInspection inspection = xmlToolkit.inspect(xml);
        sendValidationChunk(emitter, phase, inspection);
    }

    private void sendValidationChunk(ResponseBodyEmitter emitter, String phase, DrawioCanvasXmlToolkit.CanvasInspection inspection) throws Exception {
        com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
        chunk.put("type", "validation_result");
        chunk.put("valid", inspection.isValid());
        chunk.put("severity", inspection.getSeverity());
        chunk.put("issues", inspection.getIssues());
        chunk.put("content", inspection.isValid() ? "Diagram XML passed lightweight validation." : String.join("; ", inspection.getIssues()));
        sendWrappedChunk(emitter, phase, chunk);
    }

    private com.alibaba.fastjson.JSONObject firstChunkOfType(List<com.alibaba.fastjson.JSONObject> chunks, String type) {
        for (com.alibaba.fastjson.JSONObject chunk : chunks) {
            if (type.equals(chunk.getString("type"))) {
                return chunk;
            }
        }
        return null;
    }

    private boolean shouldWithholdFinalDiagram(com.alibaba.fastjson.JSONObject validationChunk) {
        return validationChunk != null
                && "validation_result".equals(validationChunk.getString("type"))
                && !validationChunk.getBooleanValue("valid")
                && isCriticalSeverity(validationChunk.getString("severity"));
    }

    private void rememberPendingDiagram(ResponseBodyEmitter emitter,
                                        String xml,
                                        DrawioCanvasXmlToolkit.CanvasInspection inspection) {
        pendingDiagrams.put(emitter, new PendingDiagram(
                xml,
                inspection.getSeverity(),
                inspection.isValid() ? "" : String.join("; ", inspection.getIssues())
        ));
    }

    private void rememberPendingDiagram(ResponseBodyEmitter emitter,
                                        String xml,
                                        com.alibaba.fastjson.JSONObject validationChunk) {
        pendingDiagrams.put(emitter, new PendingDiagram(
                xml,
                validationChunk.getString("severity"),
                StringUtils.defaultString(validationChunk.getString("content"))
        ));
    }

    private boolean isCriticalSeverity(String severity) {
        return "critical".equalsIgnoreCase(StringUtils.defaultString(severity));
    }

    private void sendError(ResponseBodyEmitter emitter, String phase, String content) throws Exception {
        com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
        wrapper.put("phase", phase);
        com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
        chunk.put("type", "error");
        chunk.put("content", content);
        wrapper.put("chunk", chunk);
        emitter.send(wrapper.toJSONString() + "\n");
    }

    private void sendWrappedChunk(ResponseBodyEmitter emitter, String phase, com.alibaba.fastjson.JSONObject chunk) throws Exception {
        com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
        wrapper.put("phase", phase);
        wrapper.put("chunk", chunk);
        emitter.send(wrapper.toJSONString() + "\n");
    }

    private boolean isForwardedChunkType(String type) {
        return "drawio_preview".equals(type)
                || "drawio_node".equals(type)
                || "drawio_edge".equals(type)
                || "drawio_done".equals(type)
                || "user".equals(type)
                || "drawio".equals(type)
                || "validation_result".equals(type)
                || "canvas_state".equals(type)
                || "cell_matches".equals(type)
                || "overlap_report".equals(type)
                || "continuation_result".equals(type);
    }

    private boolean shouldCompleteAfterFatalValidation(String type, com.alibaba.fastjson.JSONObject json) {
        if (!"validation_result".equals(type)
                || json.getBooleanValue("valid")
                || !"critical".equalsIgnoreCase(StringUtils.defaultString(json.getString("severity")))) {
            return false;
        }

        String issuesText = StringUtils.defaultString(json.getString("content")) + " "
                + StringUtils.defaultString(json.getString("issues"));
        return StringUtils.containsIgnoreCase(issuesText, "could not be parsed")
                || StringUtils.containsIgnoreCase(issuesText, "malformed")
                || StringUtils.containsIgnoreCase(issuesText, "incomplete tag");
    }

    private void sendStatus(ResponseBodyEmitter emitter, String phase, String content) throws Exception {
        com.alibaba.fastjson.JSONObject statusMsg = new com.alibaba.fastjson.JSONObject();
        com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
        chunk.put("type", "status");
        chunk.put("content", content);
        statusMsg.put("phase", phase);
        statusMsg.put("chunk", chunk);
        emitter.send(statusMsg.toJSONString() + "\n");
    }

    private void sendUserChunk(ResponseBodyEmitter emitter, String phase, String content) throws Exception {
        com.alibaba.fastjson.JSONObject wrapper = new com.alibaba.fastjson.JSONObject();
        wrapper.put("phase", phase);
        com.alibaba.fastjson.JSONObject chunk = new com.alibaba.fastjson.JSONObject();
        chunk.put("type", "user");
        chunk.put("content", content);
        wrapper.put("chunk", chunk);
        emitter.send(wrapper.toJSONString() + "\n");
    }

    private boolean processIntentJson(ResponseBodyEmitter emitter, String phase, com.alibaba.fastjson.JSONObject json) throws Exception {
        if (json == null || !json.containsKey("intent")) {
            return false;
        }

        String intent = json.getString("intent");
        if (!"answer_only".equals(intent) && !"clarify".equals(intent)) {
            return false;
        }

        sendUserChunk(emitter, phase, normalizeUserAnswer(json.getString("answer")));
        return true;
    }

    private String normalizeUserAnswer(String answer) {
        String normalized = StringUtils.trimToEmpty(answer)
                .replace("\\n", "\n")
                .replace("\\\"", "\"");
        return StringUtils.defaultIfBlank(normalized, "I can answer or review the current canvas without modifying it.");
    }

    private com.alibaba.fastjson.JSONObject extractFirstJsonObject(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }

        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        return com.alibaba.fastjson.JSON.parseObject(text.substring(start, i + 1));
                    } catch (Exception ignored) {
                        return null;
                    }
                }
            }
        }

        return null;
    }

    // Some models emit several JSON objects back-to-back with no newline (e.g. patch_cells followed by
    // review_result). Pull out every balanced top-level object so each can be dispatched by type.
    private java.util.List<com.alibaba.fastjson.JSONObject> extractAllJsonObjects(String text) {
        java.util.List<com.alibaba.fastjson.JSONObject> objects = new java.util.ArrayList<>();
        if (StringUtils.isBlank(text)) {
            return objects;
        }
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf('{', i);
            if (start < 0) {
                break;
            }
            boolean inString = false;
            boolean escaped = false;
            int depth = 0;
            int end = -1;
            for (int j = start; j < text.length(); j++) {
                char ch = text.charAt(j);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        end = j;
                        break;
                    }
                }
            }
            if (end < 0) {
                break;
            }
            try {
                objects.add(com.alibaba.fastjson.JSON.parseObject(text.substring(start, end + 1)));
            } catch (Exception ignored) {
                // Skip an unparseable span and continue scanning.
            }
            i = end + 1;
        }
        return objects;
    }

    private boolean isCompletedEmitterError(Throwable error) {
        return error instanceof IllegalStateException && error.getMessage() != null && error.getMessage().contains("ResponseBodyEmitter has already completed")
                || error.getCause() instanceof IllegalStateException && error.getCause().getMessage() != null && error.getCause().getMessage().contains("ResponseBodyEmitter has already completed");
    }

    private boolean isClientDisconnect(Throwable error) {
        return error instanceof java.io.IOException
                || (error.getMessage() != null && error.getMessage().contains("Broken pipe"))
                || error.getCause() instanceof java.io.IOException;
    }

    private static class PendingDiagram {
        private final String xml;
        private final String severity;
        private final String content;

        private PendingDiagram(String xml, String severity, String content) {
            this.xml = xml;
            this.severity = severity;
            this.content = content;
        }
    }

}
