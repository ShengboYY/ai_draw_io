package org.zipp.ai.domain.agent.service.chat;

import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import org.zipp.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasXmlToolkit;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.types.enums.ResponseCode;
import org.zipp.ai.types.exception.AppException;
import org.zipp.ai.types.util.SecretLogSanitizer;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChatService implements IChatService {

    private static final String DRAFT_DIAGRAM_STATE_KEY = "draft_diagram";

    private static final Set<String> DRAWIO_MUTATION_TOOLS = DrawioCanvasToolNames.DRAWING_RESULT_TOOL_NAMES;
    private static final DrawioCanvasXmlToolkit XML_TOOLKIT = new DrawioCanvasXmlToolkit();

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    private final Map<String, String> userSessions = new ConcurrentHashMap<>();
    private final Map<String, String> draftDiagramSnapshots = new ConcurrentHashMap<>();

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent() && !isInternalAgent(vo.getAgent())) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    @Override
    public boolean isAgentToolFree(String agentId) {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        if (agentId == null || tables == null) return false;
        return tables.values().stream()
                .filter(table -> table.getAgent() != null && agentId.equals(table.getAgent().getAgentId()))
                .map(AiAgentConfigTableVO::getModule)
                .filter(java.util.Objects::nonNull)
                .map(AiAgentConfigTableVO.Module::getAgents)
                .filter(java.util.Objects::nonNull)
                .filter(agents -> !agents.isEmpty())
                // An explicit empty allowlist physically removes all configured MCP/skill tools.
                .anyMatch(agents -> agents.stream().allMatch(agent ->
                        agent.getAllowedTools() != null && agent.getAllowedTools().isEmpty()));
    }

    private boolean isInternalAgent(AiAgentConfigTableVO.Agent agent) {
        String agentDesc = agent.getAgentDesc();
        return null != agentDesc && agentDesc.startsWith("[internal]");
    }

    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Session session = runner.sessionService().createSession(appName, userId)
                .blockingGet();
        
        String sessionId = session.id();
        // Update cache so subsequent handleMessage calls without sessionId can use this new session
        String cacheKey = userId + "_" + agentId;
        userSessions.put(cacheKey, sessionId);
        
        return sessionId;
    }

    @Override
    public String ensureSession(String agentId, String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return createSession(agentId, userId);
        }

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Session session = runner.sessionService()
                .getSession(appName, userId, sessionId, Optional.empty())
                .blockingGet();
        if (session != null) {
            return sessionId;
        }

        // Session is gone (e.g. client kept a sessionId across a backend restart). Start fresh
        // instead of letting runAsync fail with "Session not found".
        log.warn("Session not found, creating a new one. appName:{} userId:{} staleSessionId:{}",
                appName, SecretLogSanitizer.maskCapability(userId), sessionId);
        return createSession(agentId, userId);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String cacheKey = userId + "_" + agentId;
        String sessionId = userSessions.get(cacheKey);
        if (sessionId == null) {
            sessionId = createSession(agentId, userId);
        }

        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        return handleMessage(agentId, userId, sessionId, message, null);
    }

    @Override
    public List<String> handleMessage(String agentId,
                                      String userId,
                                      String sessionId,
                                      String message,
                                      AgentUsageTelemetryContext.RunContext runContext) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        AgentUsageTelemetryContext.InvocationState invocationState = AgentUsageTelemetryContext.newInvocationState(runContext);
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg,
                        RunConfig.builder().build(), invocationState.stateDelta())
                .doOnNext(event -> persistDraftDiagramState(runner, appName, userId, sessionId, event));

        List<String> outputs = new ArrayList<>();
        events.doFinally(invocationState::close)
                .blockingForEach(event -> collectEventOutput(outputs, event));

        return outputs;
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        return handleMessageStream(agentId, userId, sessionId, message, null);
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId,
                                               String userId,
                                               String sessionId,
                                               String message,
                                               AgentUsageTelemetryContext.RunContext runContext) {
        return handleMessageStream(agentId, userId, sessionId, message, runContext, Map.of());
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId,
                                               String userId,
                                               String sessionId,
                                               String message,
                                               AgentUsageTelemetryContext.RunContext runContext,
                                               Map<String, Object> initialState) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        // Enable SSE streaming mode so LLM produces partial events (per-token)
        RunConfig runConfig = RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build();
        AgentUsageTelemetryContext.InvocationState invocationState = AgentUsageTelemetryContext.newInvocationState(runContext);
        Map<String, Object> stateDelta = new HashMap<>();
        if (initialState != null) {
            stateDelta.putAll(initialState);
        }
        // Telemetry's invocation token is server-owned and must win over any caller-supplied key.
        stateDelta.putAll(invocationState.stateDelta());
        return runner.runAsync(userId, sessionId, userMsg, runConfig, stateDelta)
                .doOnNext(event -> persistDraftDiagramState(runner, appName, userId, sessionId, event))
                .doFinally(invocationState::close);
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 获取运行体
        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        // V2 turn ports reach the runtime through this overload, so the ambient run context is the
        // only correlation the telemetry plugin can get. Without the invocation token every LLM
        // span and debug payload of a V2 turn is dropped as unattributable.
        AgentUsageTelemetryContext.InvocationState invocationState = AgentUsageTelemetryContext
                .newInvocationState(AgentUsageTelemetryContext.current().orElse(null));
        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(),
                        content, RunConfig.builder().build(), invocationState.stateDelta())
                .doOnNext(event -> persistDraftDiagramState(runner, appName, chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), event));

        List<String> outputs = new ArrayList<>();
        events.doFinally(invocationState::close)
                .blockingForEach(event -> collectEventOutput(outputs, event));

        return outputs;
    }

    private void collectEventOutput(List<String> outputs, Event event) {
        String content = event.stringifyContent();
        if (content != null && !content.isBlank()) {
            outputs.add(content);
        }

        for (FunctionResponse functionResponse : event.functionResponses()) {
            if (functionResponse.response().isEmpty()) {
                continue;
            }

            Object responseJson = JSON.toJSON(functionResponse.response().get());
            if (!(responseJson instanceof JSONObject json)) {
                continue;
            }

            String functionName = functionResponse.name().orElse("");
            String toolContent = json.getString("content");
            if (toolContent != null && !toolContent.isBlank() && DRAWIO_MUTATION_TOOLS.contains(functionName)) {
                JSONObject drawioDone = new JSONObject();
                drawioDone.put("type", "drawio_done");
                drawioDone.put("content", toolContent);
                // Evaluation adapters need observed tool provenance; never infer it from expected output.
                drawioDone.put("toolName", functionName);
                drawioDone.put("toolStatus", observedToolStatus(json));
                outputs.add(drawioDone.toJSONString());
            } else if (json.getString("type") != null) {
                if (!functionName.isBlank()) {
                    json.putIfAbsent("toolName", functionName);
                    json.putIfAbsent("toolStatus", observedToolStatus(json));
                }
                outputs.add(json.toJSONString());
            }
        }
    }

    static String observedToolStatus(JSONObject response) {
        String type = response == null || response.getString("type") == null ? "" : response.getString("type");
        String status = response == null || response.getString("status") == null ? "" : response.getString("status");
        Boolean success = response == null ? null : response.getBoolean("success");
        boolean failed = "tool_error".equalsIgnoreCase(type) || "error".equalsIgnoreCase(type)
                || "failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)
                || Boolean.FALSE.equals(success);
        return failed ? "FAILED" : "SUCCESS";
    }

    private void persistDraftDiagramState(InMemoryRunner runner, String appName, String userId, String sessionId, Event event) {
        try {
            Session session = runner.sessionService()
                    .getSession(appName, userId, sessionId, Optional.empty())
                    .blockingGet();
            if (session == null) {
                return;
            }

            String snapshotKey = draftSnapshotKey(appName, userId, sessionId);
            String currentDraft = firstNonBlank(
                    draftDiagramSnapshots.get(snapshotKey),
                    stateString(session.state().get(DRAFT_DIAGRAM_STATE_KEY)));

            Optional<String> draftDiagram = extractDraftDiagram(event, currentDraft);
            if (draftDiagram.isPresent()) {
                // Keep reviewer input deterministic even when the drawer returns only a local patch.
                String xml = draftDiagram.get();
                session.state().put(DRAFT_DIAGRAM_STATE_KEY, xml);
                draftDiagramSnapshots.put(snapshotKey, xml);
                return;
            }

            // Tool-only ADK turns can apply output-key="" after the function response; restore the last
            // real draft so the next reviewer pass does not inspect an empty canvas.
            if (isBlank(stateString(session.state().get(DRAFT_DIAGRAM_STATE_KEY))) && !isBlank(currentDraft)) {
                session.state().put(DRAFT_DIAGRAM_STATE_KEY, currentDraft);
            }
        } catch (Exception e) {
            log.warn("Failed to persist draft diagram state for review. appName:{} userId:{} sessionId:{}",
                    appName, SecretLogSanitizer.maskCapability(userId), sessionId, e);
        }
    }

    static Optional<String> extractDraftDiagram(Event event) {
        return extractDraftDiagram(event, "");
    }

    static Optional<String> extractDraftDiagram(Event event, String currentDraftXml) {
        for (FunctionResponse functionResponse : event.functionResponses()) {
            if (functionResponse.response().isEmpty()) {
                continue;
            }

            Object responseJson = JSON.toJSON(functionResponse.response().get());
            if (!(responseJson instanceof JSONObject json)) {
                continue;
            }

            String functionName = functionResponse.name().orElse("");
            String toolContent = json.getString("content");
            if (toolContent != null && !toolContent.isBlank() && DRAWIO_MUTATION_TOOLS.contains(functionName)) {
                return Optional.of(toolContent);
            }
            Optional<String> patchedDraft = mergePatchCells(json, currentDraftXml);
            if (patchedDraft.isPresent()) {
                return patchedDraft;
            }
        }

        String content = event.stringifyContent();
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }

        try {
            JSONObject json = JSON.parseObject(content);
            if ("drawio_done".equals(json.getString("type"))) {
                String drawioContent = json.getString("content");
                if (drawioContent != null && !drawioContent.isBlank()) {
                    return Optional.of(drawioContent);
                }
            }
            return mergePatchCells(json, currentDraftXml);
        } catch (Exception ignored) {
            // Some streaming chunks are partial text. They are not safe draft sources.
        }

        return Optional.empty();
    }

    private static Optional<String> mergePatchCells(JSONObject json, String currentDraftXml) {
        if (json == null || isBlank(currentDraftXml)) {
            return Optional.empty();
        }
        String type = json.getString("type");
        String mode = json.getString("mode");
        String cells = json.getString("cells");
        boolean localCellMutation = DrawioCanvasToolNames.PATCH_CELLS.equals(type)
                || (DrawioCanvasToolNames.MODIFY_DIAGRAM.equals(type)
                && List.of("patch", "append", "replace_cells").contains(mode));
        if (!localCellMutation || isBlank(cells)) {
            return Optional.empty();
        }
        String merged = XML_TOOLKIT.replaceCells(currentDraftXml, cells);
        return isBlank(merged) ? Optional.empty() : Optional.of(merged);
    }

    private static String draftSnapshotKey(String appName, String userId, String sessionId) {
        return appName + ":" + userId + ":" + sessionId;
    }

    private static String stateString(Object value) {
        return value instanceof String text ? text : "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
