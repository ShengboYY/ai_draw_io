package org.zipp.ai.trigger.http.service;

import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.api.dto.ChatResponseDTO;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingCommand;
import org.zipp.ai.domain.agent.model.valobj.intent.IntentRoutingResult;
import org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.review.CanvasReviewContext;
import org.zipp.ai.domain.agent.service.ICanvasReviewService;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.IIntentRoutingService;
import org.zipp.ai.domain.agent.service.chat.CustomApiConfigManager;
import com.alibaba.fastjson.JSON;
import io.reactivex.rxjava3.disposables.Disposable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class AgentConversationService {

    private static final int DEFAULT_MAX_REVIEW_ITERATIONS = 2;
    private static final int MAX_REVIEW_ITERATIONS_LIMIT = 3;

    @Resource
    private IChatService chatService;

    @Resource
    private IIntentRoutingService intentRoutingService;

    @Resource
    private ICanvasReviewService canvasReviewService;

    @Resource
    private DrawioPromptContextBuilder promptContextBuilder;

    @Resource
    private DrawioStreamResponseWriter streamResponseWriter;

    @Resource
    private SkillContentProvider skillContentProvider;

    public ChatResponseDTO chat(ChatRequestDTO requestDTO) {
        String sessionId = ensureSession(requestDTO);
        CustomApiConfigManager.CustomApiConfig config = buildCustomApiConfig(requestDTO);
        CustomApiConfigManager.setConfig(sessionId, config);

        IntentRoutingResult routingResult = routeIntent(requestDTO, config);
        if (routingResult.isDirectReply()) {
            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setType("user");
            responseDTO.setContent(resolveDirectAnswer(requestDTO, config, routingResult));
            return responseDTO;
        }

        CanvasReviewContext reviewContext = buildReviewContextIfNeeded(requestDTO, config, routingResult);
        int maxReviewIterations = effectiveMaxReviewIterations(requestDTO, routingResult);
        String routedMessage = buildRoutedMessage(requestDTO, routingResult, reviewContext, maxReviewIterations, requestDTO.getUserId(), requestDTO.getSkills());
        List<String> messages = chatService.handleMessage(requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, routedMessage);
        return parseChatResponse(messages);
    }

    public void stream(ChatRequestDTO requestDTO, ResponseBodyEmitter emitter) {
        try {
            String sessionId = ensureSession(requestDTO);
            final String finalSessionId = sessionId;

            CustomApiConfigManager.CustomApiConfig config = buildCustomApiConfig(requestDTO);
            CustomApiConfigManager.setConfig(finalSessionId, config);

            IntentRoutingResult routingResult = routeIntent(requestDTO, config);
            if (routingResult.isDirectReply()) {
                streamResponseWriter.sendDirectReply(emitter, resolveDirectAnswer(requestDTO, config, routingResult));
                return;
            }

            // Each author has its own buffer because the ADK stream can interleave partial chunks.
            final ConcurrentHashMap<String, StringBuilder> authorBuffers = new ConcurrentHashMap<>();
            final int maxReviewIterations = effectiveMaxReviewIterations(requestDTO, routingResult);
            final AtomicInteger reviewIterationCounter = new AtomicInteger(0);
            final AtomicReference<String> lastPhaseRef = new AtomicReference<>("");
            final AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            final AtomicBoolean manuallyCompleted = new AtomicBoolean(false);
            final CanvasReviewContext reviewContext = buildReviewContextIfNeeded(requestDTO, config, routingResult);
            final String routedMessage = buildRoutedMessage(requestDTO, routingResult, reviewContext, maxReviewIterations, requestDTO.getUserId(), requestDTO.getSkills());
            // The current canvas travels in the request; keep it so patch_cells can merge a delta
            // without the model re-emitting the whole diagram.
            final String currentCanvasXml = contextBuilder().resolveCanvasXml(requestDTO);
            streamResponseWriter.setCurrentCanvas(emitter, currentCanvasXml);

            Disposable disposable = chatService.handleMessageStream(requestDTO.getAgentId(), requestDTO.getUserId(), finalSessionId, routedMessage)
                    .subscribe(
                            event -> {
                                try {
                                    String author = event.author();
                                    String phase = streamResponseWriter.resolvePhase(author);
                                    if (shouldStopForReviewLimit(phase, lastPhaseRef, reviewIterationCounter, maxReviewIterations)) {
                                        // The drawer's final output may still be buffered (e.g. a patch_cells
                                        // line carries no <mxGraphModel> to flush mid-stream). Emit it before
                                        // we stop, otherwise a localized edit would be silently dropped.
                                        flushAuthorBuffers(emitter, authorBuffers);
                                        completeStream(emitter, manuallyCompleted, disposableRef);
                                        return;
                                    }

                                    if (!event.functionResponses().isEmpty()) {
                                        if (processFunctionResponses(emitter, phase, event, currentCanvasXml)) {
                                            return;
                                        }
                                    }

                                    if (!event.functionCalls().isEmpty()) {
                                        return;
                                    }

                                    String content = event.stringifyContent();
                                    if (content == null || content.isEmpty()) {
                                        return;
                                    }

                                    boolean isPartial = event.partial().orElse(false);
                                    StringBuilder buffer = authorBuffers.computeIfAbsent(author, k -> new StringBuilder());
                                    String currentActiveLine = currentActiveLine(buffer).trim();
                                    boolean isLikelyJson = currentActiveLine.startsWith("{") || (currentActiveLine.isEmpty() && content.trim().startsWith("{"));

                                    if (!isLikelyJson) {
                                        streamResponseWriter.sendToken(emitter, phase, content);
                                    }

                                    buffer.append(content);
                                    String accumulated = buffer.toString();
                                    String bufferedDrawioXml = streamResponseWriter.extractDrawioXml(accumulated);
                                    if ("drawing".equals(phase) && StringUtils.isNotBlank(bufferedDrawioXml)) {
                                        // Some models emit multiline raw XML instead of strict NDJSON.
                                        buffer.setLength(0);
                                        streamResponseWriter.sendDrawioStream(emitter, phase, bufferedDrawioXml);
                                        return;
                                    }
                                    if ("drawing".equals(phase) && streamResponseWriter.isIncompleteDrawioXml(accumulated)) {
                                        return;
                                    }

                                    flushCompleteLines(emitter, phase, isPartial, buffer, accumulated, manuallyCompleted, disposableRef);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            error -> streamResponseWriter.handleStreamError(emitter, manuallyCompleted.get(), error),
                            () -> handleStreamComplete(emitter, authorBuffers, manuallyCompleted)
                    );
            disposableRef.set(disposable);
            if (manuallyCompleted.get() && !disposable.isDisposed()) {
                disposable.dispose();
            }

            emitter.onCompletion(() -> {
                streamResponseWriter.clearPendingDiagram(emitter);
                disposeStream(finalSessionId, "emitter.onCompletion", disposable);
            });
            emitter.onTimeout(() -> {
                streamResponseWriter.clearPendingDiagram(emitter);
                disposeStream(finalSessionId, "emitter.onTimeout", disposable);
            });
            emitter.onError(e -> {
                streamResponseWriter.clearPendingDiagram(emitter);
                disposeStream(finalSessionId, "emitter.onError", disposable);
            });
        } catch (Exception e) {
            log.error("流式对话失败", e);
            emitter.completeWithError(e);
        }
    }

    private String ensureSession(ChatRequestDTO requestDTO) {
        // Validate the client-supplied sessionId; a stale id (e.g. after a backend restart that
        // wiped in-memory sessions) is transparently replaced with a fresh session.
        return chatService.ensureSession(requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId());
    }

    private ChatResponseDTO parseChatResponse(List<String> messages) {
        ChatResponseDTO responseDTO = new ChatResponseDTO();
        try {
            String result = messages.stream().reduce((first, second) -> second).orElse("");
            ChatResponseDTO parsed = JSON.parseObject(result, ChatResponseDTO.class);
            if (null != parsed) {
                responseDTO = parsed;
                if (null == responseDTO.getType()) {
                    responseDTO.setType("user");
                }
            } else {
                responseDTO.setType("user");
                responseDTO.setContent(String.join("\n", messages));
            }
        } catch (Exception e) {
            responseDTO.setType("user");
            responseDTO.setContent(String.join("\n", messages));
        }
        return responseDTO;
    }

    private int effectiveMaxReviewIterations(ChatRequestDTO requestDTO, IntentRoutingResult routingResult) {
        // Localized patches skip the review/revision loop entirely: with 0 iterations the stream
        // completes as soon as the edited canvas is flushed, instead of waiting on review rounds.
        if (routingResult != null
                && "patch_existing".equals(routingResult.getTaskType())
                && !routingResult.needsCanvasReview()) {
            return 0;
        }
        return normalizeMaxReviewIterations(requestDTO.getMaxReviewIterations());
    }

    private int normalizeMaxReviewIterations(Integer maxReviewIterations) {
        if (maxReviewIterations == null) {
            return DEFAULT_MAX_REVIEW_ITERATIONS;
        }
        // Frontend owns the setting; backend clamps it to avoid runaway review loops.
        return Math.max(0, Math.min(maxReviewIterations, MAX_REVIEW_ITERATIONS_LIMIT));
    }

    private CustomApiConfigManager.CustomApiConfig buildCustomApiConfig(ChatRequestDTO requestDTO) {
        return CustomApiConfigManager.CustomApiConfig.builder()
                .baseUrl(requestDTO.getCustomBaseUrl())
                .apiKey(requestDTO.getCustomApiKey())
                .completionsPath(requestDTO.getCustomCompletionsPath())
                .model(requestDTO.getCustomModel())
                .customModelSelected(StringUtils.isNotBlank(requestDTO.getCustomModel()))
                .build();
    }

    private IntentRoutingResult routeIntent(ChatRequestDTO requestDTO, CustomApiConfigManager.CustomApiConfig config) {
        DrawioPromptContextBuilder contextBuilder = contextBuilder();
        String canvasXml = contextBuilder.resolveCanvasXml(requestDTO);
        return intentRoutingService.route(IntentRoutingCommand.builder()
                .userId(requestDTO.getUserId())
                .message(contextBuilder.buildIntentMessage(requestDTO))
                .canvasXml(canvasXml)
                .canvasSummary(contextBuilder.resolveCanvasSummary(requestDTO, canvasXml))
                .customApiConfig(config)
                .build());
    }

    private String resolveDirectAnswer(ChatRequestDTO requestDTO, CustomApiConfigManager.CustomApiConfig config, IntentRoutingResult routingResult) {
        if (!routingResult.needsCanvasReview()) {
            return routingResult.getAnswer();
        }
        return canvasReviewService.answer(buildCanvasReviewCommand(requestDTO, config, routingResult));
    }

    private CanvasReviewContext buildReviewContextIfNeeded(ChatRequestDTO requestDTO,
                                                           CustomApiConfigManager.CustomApiConfig config,
                                                           IntentRoutingResult routingResult) {
        if (!routingResult.needsCanvasReview()) {
            return null;
        }
        return canvasReviewService.buildReviewContext(buildCanvasReviewCommand(requestDTO, config, routingResult));
    }

    private CanvasReviewCommand buildCanvasReviewCommand(ChatRequestDTO requestDTO,
                                                         CustomApiConfigManager.CustomApiConfig config,
                                                         IntentRoutingResult routingResult) {
        return CanvasReviewCommand.builder()
                .userId(requestDTO.getUserId())
                .message(contextBuilder().buildReviewContextMessage(requestDTO, routingResult))
                .routingResult(routingResult)
                .customApiConfig(config)
                .build();
    }

    // Inject skill rules only for generative draws; patch_existing stays lean for the fast edit path.
    // User-specified skills (if any) override the router's automatic selection.
    private String skillSectionFor(IntentRoutingResult routingResult, String ownerId, List<String> userSkills) {
        String taskType = StringUtils.defaultString(routingResult.getTaskType());
        boolean generativeDraw = "create_new".equals(taskType)
                || "append_existing".equals(taskType)
                || "optimize_layout".equals(taskType)
                || "fallback_full_xml".equals(taskType);
        if (!generativeDraw) {
            return "";
        }
        List<String> chosen = (userSkills != null && !userSkills.isEmpty())
                ? userSkills
                : List.of(StringUtils.defaultString(routingResult.getSkillName()));
        String section = skillContentProvider.buildSkillSection(chosen, ownerId);
        log.info("[skill-inject] taskType={} chosenSkills={} userSpecified={} ownerId={} injectedChars={}",
                taskType, chosen, userSkills != null && !userSkills.isEmpty(), ownerId, section.length());
        return section;
    }

    private String buildRoutedMessage(ChatRequestDTO requestDTO,
                                      IntentRoutingResult routingResult,
                                      CanvasReviewContext reviewContext,
                                      int maxReviewIterations,
                                      String ownerId,
                                      List<String> userSkills) {
        com.alibaba.fastjson.JSONObject routingJson = new com.alibaba.fastjson.JSONObject();
        routingJson.put("intent", routingResult.getIntent());
        routingJson.put("drawMode", routingResult.getDrawMode());
        routingJson.put("diagramType", routingResult.getDiagramType());
        routingJson.put("skillName", routingResult.getSkillName());
        routingJson.put("taskType", routingResult.getTaskType());
        routingJson.put("needsCanvasQuality", routingResult.getNeedsCanvasQuality());
        routingJson.put("needsSemanticReview", routingResult.getNeedsSemanticReview());
        routingJson.put("answerMode", routingResult.getAnswerMode());
        routingJson.put("reason", routingResult.getReason());
        routingJson.put("maxReviewIterations", maxReviewIterations);
        routingJson.put("allowedTools", allowedToolsFor(routingResult));
        routingJson.put("toolPolicy", "Use only allowedTools for this turn unless a review_result explicitly requires a narrower fix_strategy tool.");

        String routedMessage = "[Intent Routing Result]\n"
                + routingJson.toJSONString()
                + "\n\n"
                + skillSectionFor(routingResult, ownerId, userSkills)
                + contextBuilder().buildDrawingContextMessage(requestDTO, routingResult);
        if (null == reviewContext) {
            return routedMessage;
        }

        return routedMessage
                + "\n\n"
                + reviewContext.getSerializedContext();
    }

    private List<String> allowedToolsFor(IntentRoutingResult routingResult) {
        String taskType = StringUtils.defaultString(routingResult.getTaskType());
        return switch (taskType) {
            case "create_new", "fallback_full_xml" -> List.of("display_diagram", "validate_diagram", "continue_diagram");
            case "patch_existing" -> List.of("patch_cells", "find_cells", "get_canvas_state", "update_cells", "edit_diagram", "validate_diagram");
            case "append_existing" -> List.of("get_canvas_state", "append_diagram", "validate_diagram", "continue_diagram");
            case "optimize_layout" -> List.of("get_canvas_state", "detect_overlaps", "route_edges", "optimize_diagram", "validate_diagram");
            case "review_only", "none" -> List.of("get_canvas_state", "validate_diagram", "detect_overlaps");
            default -> List.of("display_diagram", "append_diagram", "edit_diagram", "optimize_diagram", "update_cells", "route_edges", "validate_diagram");
        };
    }

    private String buildIntentMessage(ChatRequestDTO requestDTO) {
        return contextBuilder().buildIntentMessage(requestDTO);
    }

    private String buildDrawingContextMessage(ChatRequestDTO requestDTO) {
        return contextBuilder().buildDrawingContextMessage(requestDTO, null);
    }

    private DrawioPromptContextBuilder contextBuilder() {
        return null == promptContextBuilder ? new DrawioPromptContextBuilder() : promptContextBuilder;
    }

    private String currentActiveLine(StringBuilder buffer) {
        int lastNewline = buffer.lastIndexOf("\n");
        if (lastNewline >= 0) {
            return buffer.substring(lastNewline + 1);
        }
        return buffer.toString();
    }

    private boolean processFunctionResponses(ResponseBodyEmitter emitter,
                                             String phase,
                                             com.google.adk.events.Event event,
                                             String currentCanvasXml) throws Exception {
        boolean processed = false;
        for (com.google.genai.types.FunctionResponse functionResponse : event.functionResponses()) {
            if (functionResponse.response().isEmpty()) {
                continue;
            }

            Object responseJson = com.alibaba.fastjson.JSON.toJSON(functionResponse.response().get());
            if (!(responseJson instanceof com.alibaba.fastjson.JSONObject json)) {
                continue;
            }

            String functionName = functionResponse.name().orElse("display_diagram");
            // patch_cells returns only the changed fragment; merge it into the canvas we already hold.
            if ("patch_cells".equals(functionName)) {
                if (streamResponseWriter.sendLocalCellPatch(emitter, phase, currentCanvasXml, json.getString("cells"))) {
                    return true;
                }
                processed = true;
                continue;
            }
            String content = json.getString("content");
            if (StringUtils.isNotBlank(content) && streamResponseWriter.supportsToolCall(functionName)) {
                com.alibaba.fastjson.JSONObject toolJson = new com.alibaba.fastjson.JSONObject();
                toolJson.put("type", functionName);
                toolJson.put("xml", content);
                json = toolJson;
            } else if (StringUtils.isBlank(json.getString("type"))) {
                continue;
            }

            if (streamResponseWriter.processAndSendLine(emitter, phase, json.toJSONString())) {
                return true;
            }
            processed = true;
        }
        return processed;
    }

    private void flushCompleteLines(ResponseBodyEmitter emitter,
                                    String phase,
                                    boolean isPartial,
                                    StringBuilder buffer,
                                    String accumulated,
                                    AtomicBoolean manuallyCompleted,
                                    AtomicReference<Disposable> disposableRef) throws Exception {
        if (!isPartial || accumulated.contains("\n")) {
            String[] lines = accumulated.split("\n", -1);
            String remaining = lines[lines.length - 1];

            buffer.setLength(0);
            if (!remaining.isEmpty()) {
                buffer.append(remaining);
            }

            int processUpTo = isPartial ? lines.length - 1 : lines.length;
            for (int i = 0; i < processUpTo; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (streamResponseWriter.processAndSendLine(emitter, phase, line)) {
                    completeStream(emitter, manuallyCompleted, disposableRef);
                    return;
                }
            }
        }

        if (!isPartial) {
            String remaining = buffer.toString().trim();
            buffer.setLength(0);
            if (!remaining.isEmpty() && streamResponseWriter.processAndSendLine(emitter, phase, remaining)) {
                completeStream(emitter, manuallyCompleted, disposableRef);
            }
        }
    }

    private boolean shouldStopForReviewLimit(String phase,
                                             AtomicReference<String> lastPhaseRef,
                                             AtomicInteger reviewIterationCounter,
                                             int maxReviewIterations) {
        String previousPhase = lastPhaseRef.getAndSet(phase);
        // Each reviewing phase means a new review/revision round has started.
        return "reviewing".equals(phase)
                && !"reviewing".equals(previousPhase)
                && reviewIterationCounter.incrementAndGet() > maxReviewIterations;
    }

    private void completeStream(ResponseBodyEmitter emitter,
                                AtomicBoolean manuallyCompleted,
                                AtomicReference<Disposable> disposableRef) {
        if (!manuallyCompleted.compareAndSet(false, true)) {
            return;
        }
        try {
            streamResponseWriter.flushPendingDiagram(emitter, "done");
        } catch (Exception ignored) {
        }
        try {
            streamResponseWriter.sendDone(emitter);
        } catch (Exception ignored) {
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }

        Disposable disposable = disposableRef.get();
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    private void handleStreamComplete(ResponseBodyEmitter emitter,
                                      ConcurrentHashMap<String, StringBuilder> authorBuffers,
                                      AtomicBoolean manuallyCompleted) {
        if (manuallyCompleted.get()) {
            return;
        }
        flushAuthorBuffers(emitter, authorBuffers);
        try {
            streamResponseWriter.flushPendingDiagram(emitter, "done");
            streamResponseWriter.sendDone(emitter);
        } catch (Exception ignored) {
        }
        emitter.complete();
    }

    // Emit any buffered author output (non-XML lines such as patch_cells are only complete at flush time).
    private void flushAuthorBuffers(ResponseBodyEmitter emitter, ConcurrentHashMap<String, StringBuilder> authorBuffers) {
        for (StringBuilder buf : authorBuffers.values()) {
            String remaining = buf.toString().trim();
            buf.setLength(0);
            if (!remaining.isEmpty()) {
                try {
                    streamResponseWriter.processAndSendLine(emitter, "done", remaining);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void disposeStream(String sessionId, String eventName, Disposable disposable) {
        log.info("流式对话 {} sessionId:{}", eventName, sessionId);
        if (!disposable.isDisposed()) {
            disposable.dispose();
        }
    }

}
