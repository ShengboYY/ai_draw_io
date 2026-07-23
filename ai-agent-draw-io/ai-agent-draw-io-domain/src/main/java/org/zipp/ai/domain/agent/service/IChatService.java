package org.zipp.ai.domain.agent.service;

import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;
import java.util.Map;

/**
 * 对话接口
 */
public interface IChatService {

    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    /**
     * Security boundary for callers that pass untrusted image or document content to an agent.
     * Implementations must return true only when every reachable LLM agent has an empty tool set.
     */
    default boolean isAgentToolFree(String agentId) {
        return false;
    }

    String createSession(String agentId, String userId);

    /**
     * Return a usable sessionId: reuse the given one if it still exists in the runner's
     * session service, otherwise create a fresh session. Guards against stale session ids
     * left over in clients after a backend restart (in-memory sessions are wiped on restart).
     */
    String ensureSession(String agentId, String userId, String sessionId);

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    default List<String> handleMessage(String agentId,
                                       String userId,
                                       String sessionId,
                                       String message,
                                       AgentUsageTelemetryContext.RunContext runContext) {
        return handleMessage(agentId, userId, sessionId, message);
    }

    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    default Flowable<Event> handleMessageStream(String agentId,
                                                String userId,
                                                String sessionId,
                                                String message,
                                                AgentUsageTelemetryContext.RunContext runContext) {
        return handleMessageStream(agentId, userId, sessionId, message);
    }

    default Flowable<Event> handleMessageStream(String agentId,
                                                String userId,
                                                String sessionId,
                                                String message,
                                                AgentUsageTelemetryContext.RunContext runContext,
                                                Map<String, Object> initialState) {
        return handleMessageStream(agentId, userId, sessionId, message, runContext);
    }

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

}
