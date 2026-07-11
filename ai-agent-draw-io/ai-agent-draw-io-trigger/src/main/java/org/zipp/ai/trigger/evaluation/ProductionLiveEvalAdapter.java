package org.zipp.ai.trigger.evaluation;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.api.dto.ChatResponseDTO;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.service.evaluation.EvalInfrastructureException;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;
import org.zipp.ai.trigger.http.service.AgentConversationService;

import java.util.List;
import java.util.UUID;

/** Explicit Mode C adapter. Creating this bean is safe; model calls occur only when execute is invoked. */
@Component
public class ProductionLiveEvalAdapter implements LiveEvalRunner.LiveExecutionFactory {
    private final AgentConversationService conversationService;
    private final IAgentUsageTelemetryStore telemetryStore;
    private final String agentId;
    private final String gitSha;

    public ProductionLiveEvalAdapter(AgentConversationService conversationService,
                                     IAgentUsageTelemetryStore telemetryStore,
                                     @Value("${zipp.evaluation.live-agent-id:300000}") String agentId,
                                     @Value("${GIT_SHA:unknown}") String gitSha) {
        this.conversationService = conversationService; this.telemetryStore = telemetryStore;
        this.agentId = agentId; this.gitSha = gitSha;
    }

    @Override
    public EvalExecution execute(EvalCaseDefinition evalCase) {
        Object user = evalCase.getInput().get("user");
        if (!(user instanceof String message) || StringUtils.isBlank(message)) {
            throw new IllegalArgumentException("Production Mode C currently requires input.user");
        }
        String initialXml = evalCase.getReplay() == null ? null : evalCase.getReplay().getInitialCanvasXml();
        EvalCaseDefinition.ExecutionProfile profile = profile(evalCase);
        ChatRequestDTO request = new ChatRequestDTO();
        request.setAgentId(agentId); request.setUserId("eval-system");
        request.setSessionId("eval-session-" + UUID.randomUUID()); request.setRequestId("eval-request-" + UUID.randomUUID());
        request.setMessage(message); request.setCanvasXml(initialXml); request.setMaxReviewIterations(profile.getMaxReviewIterations());
        request.setModelCredentialId(profile.getModelCredentialId());
        try {
            ChatResponseDTO response = conversationService.chat(request);
            AgentRunDetail detail = telemetryStore.findRunDetail(response.getRunId())
                    .orElseThrow(() -> new EvalInfrastructureException("live run telemetry unavailable"));
            String finalXml = looksLikeXml(response.getContent()) ? response.getContent() : initialXml;
            long inputTokens = detail.getLlmCalls().stream().mapToLong(call -> call.getPromptTokens() == null ? 0 : call.getPromptTokens()).sum();
            long outputTokens = detail.getLlmCalls().stream().mapToLong(call -> call.getCompletionTokens() == null ? 0 : call.getCompletionTokens()).sum();
            return EvalExecution.builder().evalCase(evalCase).trace(project(detail, initialXml, finalXml))
                    .initialCanvasXml(initialXml).finalCanvasXml(finalXml).responseText(response.getContent()).gitSha(gitSha)
                    .executionProfileHash(profile.getProfileId()).promptConfigHash(profile.getPromptConfigHash())
                    .skillCatalogHash(profile.getSkillCatalogHash()).toolPolicyVersion(profile.getToolPolicyVersion())
                    .inputTokens(inputTokens).outputTokens(outputTokens).estimatedCost(estimatedCost(profile, inputTokens, outputTokens))
                    .build();
        } catch (EvalInfrastructureException e) {
            throw e;
        } catch (RuntimeException e) {
            if (isTransient(e)) throw new EvalInfrastructureException("transient live-model failure", e);
            throw e;
        }
    }

    private EvalTrace project(AgentRunDetail detail, String before, String after) {
        AgentTraceEvent routingEvent = detail.getTraceEvents().stream()
                .filter(event -> "ROUTING_DECIDED".equals(event.getEventType())).findFirst().orElse(null);
        JSONObject routing = routingEvent == null || StringUtils.isBlank(routingEvent.getMetadataJson())
                ? new JSONObject() : JSON.parseObject(routingEvent.getMetadataJson());
        List<EvalTrace.ToolCall> tools = detail.getToolCalls().stream().map(call -> EvalTrace.ToolCall.builder()
                .name(call.getToolName()).status(failed(call.getStatus()) ? EvalTrace.RunStatus.FAILED : EvalTrace.RunStatus.SUCCESS)
                .build()).toList();
        boolean runFailed = failed(detail.getRun().getStatus());
        return EvalTrace.builder().runStatus(runFailed ? EvalTrace.RunStatus.FAILED : EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(runFailed ? EvalTrace.TaskOutcome.UNKNOWN : EvalTrace.TaskOutcome.FULFILLED)
                .routing(EvalTrace.Routing.builder().routeType(routing.getString("routeType"))
                        .diagramType(routing.getString("diagramType")).answerMode(routing.getString("answerMode"))
                        .needsCanvasQuality(routing.getBoolean("needsCanvasQuality"))
                        .needsSemanticReview(routing.getBoolean("needsSemanticReview")).build())
                .toolCalls(tools).beforeCanvasHash(hash(before)).afterCanvasHash(hash(after)).build();
    }

    private EvalCaseDefinition.ExecutionProfile profile(EvalCaseDefinition evalCase) {
        return evalCase.getExecutionProfile() == null ? new EvalCaseDefinition.ExecutionProfile() : evalCase.getExecutionProfile();
    }
    private boolean failed(String status) { return "FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status); }
    private boolean looksLikeXml(String value) { return StringUtils.containsIgnoreCase(value, "<mxGraphModel") || StringUtils.containsIgnoreCase(value, "<mxfile"); }
    private String hash(String value) { return value == null ? null : Integer.toHexString(value.hashCode()); }
    private double estimatedCost(EvalCaseDefinition.ExecutionProfile profile, long inputTokens, long outputTokens) {
        double inputPrice = profile.getInputPricePerMillion() == null ? 0D : profile.getInputPricePerMillion();
        double outputPrice = profile.getOutputPricePerMillion() == null ? 0D : profile.getOutputPricePerMillion();
        return inputTokens * inputPrice / 1_000_000D + outputTokens * outputPrice / 1_000_000D;
    }
    private boolean isTransient(Throwable error) {
        String message = StringUtils.defaultString(error.getMessage()).toLowerCase();
        return message.contains("429") || message.contains("timeout") || message.contains("temporar") || message.contains("503");
    }
}
