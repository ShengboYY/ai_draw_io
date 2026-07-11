package org.zipp.ai.trigger.evaluation;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.zipp.ai.api.dto.ChatRequestDTO;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.evaluation.*;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentRunDetail;
import org.zipp.ai.domain.agent.model.valobj.usage.AgentTraceEvent;
import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.agent.service.canvas.CanvasXmlContentHasher;
import org.zipp.ai.domain.agent.service.evaluation.EvalInfrastructureException;
import org.zipp.ai.domain.agent.service.evaluation.LiveEvalRunner;
import org.zipp.ai.domain.agent.service.usage.IAgentUsageTelemetryStore;
import org.zipp.ai.trigger.http.service.AgentConversationService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit Mode C adapter. Creating this bean is safe; model calls occur only when execute is invoked. */
@Component
@Slf4j
public class ProductionLiveEvalAdapter implements LiveEvalRunner.LiveExecutionFactory {
    private final AgentConversationService conversationService;
    private final IAgentUsageTelemetryStore telemetryStore;
    private final ICanvasStateStore canvasStateStore;
    private final String agentId;
    private final String gitSha;
    private final long timeoutMs;
    private final CanvasXmlContentHasher contentHasher = new CanvasXmlContentHasher();

    public ProductionLiveEvalAdapter(AgentConversationService conversationService,
                                     IAgentUsageTelemetryStore telemetryStore,
                                     ICanvasStateStore canvasStateStore,
                                     @Value("${zipp.evaluation.live-agent-id:300000}") String agentId,
                                     @Value("${GIT_SHA:unknown}") String gitSha,
                                     @Value("${zipp.evaluation.live-timeout-ms:120000}") long timeoutMs) {
        this.conversationService = conversationService; this.telemetryStore = telemetryStore;
        this.canvasStateStore = canvasStateStore; this.agentId = agentId; this.gitSha = gitSha;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public EvalExecution execute(EvalCaseDefinition evalCase) {
        Object user = evalCase.getInput().get("user");
        if (!(user instanceof String message) || StringUtils.isBlank(message)) {
            throw new IllegalArgumentException("Production Mode C currently requires input.user");
        }
        String initialXml = evalCase.getReplay() == null ? null : evalCase.getReplay().getInitialCanvasXml();
        EvalCaseDefinition.ExecutionProfile profile = profile(evalCase);
        String userId = "eval-system";
        String diagramId = "eval-diagram-" + UUID.randomUUID();
        try {
            CanvasState seeded = seedInitialCanvas(userId, diagramId, initialXml);
            ChatRequestDTO request = new ChatRequestDTO();
            request.setAgentId(agentId); request.setUserId(userId); request.setDiagramId(diagramId);
            request.setSessionId("eval-session-" + UUID.randomUUID()); request.setRequestId("eval-request-" + UUID.randomUUID());
            request.setRunId("eval-run-" + UUID.randomUUID());
            request.setMessage(message); request.setCanvasXml(initialXml); request.setMaxReviewIterations(profile.getMaxReviewIterations());
            request.setExpectedVersion(seeded == null ? null : seeded.getVersion());
            request.setModelCredentialId(profile.getModelCredentialId());
            CapturingEmitter emitter = new CapturingEmitter(timeoutMs);
            conversationService.stream(request, emitter);
            emitter.awaitCompletion();
            if (emitter.error() != null) throw new EvalInfrastructureException("live stream failed", emitter.error());
            String runId = StringUtils.defaultIfBlank(emitter.runId(), request.getRunId());
            AgentRunDetail detail = telemetryStore.findRunDetail(runId)
                    .orElseThrow(() -> new EvalInfrastructureException("live run telemetry unavailable"));
            String finalXml = finalCanvas(evalCase, userId, diagramId, initialXml);
            EvalTrace trace = project(detail, evalCase, initialXml, finalXml);
            long inputTokens = detail.getLlmCalls().stream().mapToLong(call -> call.getPromptTokens() == null ? 0 : call.getPromptTokens()).sum();
            long outputTokens = detail.getLlmCalls().stream().mapToLong(call -> call.getCompletionTokens() == null ? 0 : call.getCompletionTokens()).sum();
            return EvalExecution.builder().evalCase(evalCase).trace(trace)
                    .initialCanvasXml(initialXml).finalCanvasXml(finalXml).responseText(emitter.responseText()).gitSha(gitSha)
                    .executionProfileHash(profile.getProfileId()).promptConfigHash(profile.getPromptConfigHash())
                    .skillCatalogHash(profile.getSkillCatalogHash()).toolPolicyVersion(profile.getToolPolicyVersion())
                    .inputTokens(inputTokens).outputTokens(outputTokens).estimatedCost(estimatedCost(profile, inputTokens, outputTokens))
                    .build();
        } catch (EvalInfrastructureException e) {
            throw e;
        } catch (RuntimeException e) {
            if (isTransient(e)) throw new EvalInfrastructureException("transient live-model failure", e);
            throw e;
        } finally {
            cleanupCanvas(userId, diagramId);
        }
    }

    private CanvasState seedInitialCanvas(String userId, String diagramId, String initialXml) {
        if (StringUtils.isBlank(initialXml)) return null;
        CanvasState seeded = canvasStateStore.save(CanvasState.builder().userId(userId).diagramId(diagramId)
                .title("Evaluation fixture").currentXml(initialXml).build());
        if (seeded == null) throw new EvalInfrastructureException("failed to seed live evaluation canvas");
        return seeded;
    }

    private String finalCanvas(EvalCaseDefinition evalCase, String userId, String diagramId, String initialXml) {
        CanvasState state = canvasStateStore.find(userId, diagramId).orElse(null);
        boolean mutationExpected = Boolean.TRUE.equals(evalCase.getExpected().getRequireCanvasChange());
        if (state == null || StringUtils.isBlank(state.getCurrentXml())) {
            if (mutationExpected) throw new EvalInfrastructureException("live mutation produced no persisted canvas");
            return initialXml;
        }
        return state.getCurrentXml();
    }

    private EvalTrace project(AgentRunDetail detail, EvalCaseDefinition evalCase, String before, String after) {
        AgentTraceEvent routingEvent = detail.getTraceEvents().stream()
                .filter(event -> "ROUTING_DECIDED".equals(event.getEventType())).findFirst().orElse(null);
        JSONObject routing = routingEvent == null || StringUtils.isBlank(routingEvent.getMetadataJson())
                ? new JSONObject() : JSON.parseObject(routingEvent.getMetadataJson());
        List<EvalTrace.ToolCall> tools = detail.getToolCalls().stream().map(call -> EvalTrace.ToolCall.builder()
                .name(call.getToolName()).status(failed(call.getStatus()) ? EvalTrace.RunStatus.FAILED : EvalTrace.RunStatus.SUCCESS)
                .build()).toList();
        boolean runFailed = failed(detail.getRun().getStatus());
        EvalTrace.TaskOutcome outcome = observedOutcome(evalCase, routing.getString("routeType"), runFailed, before, after);
        return EvalTrace.builder().runStatus(runFailed ? EvalTrace.RunStatus.FAILED : EvalTrace.RunStatus.SUCCESS)
                .taskOutcome(outcome)
                .routing(EvalTrace.Routing.builder().routeType(routing.getString("routeType"))
                        .diagramType(routing.getString("diagramType")).answerMode(routing.getString("answerMode"))
                        .needsCanvasQuality(routing.getBoolean("needsCanvasQuality"))
                        .needsSemanticReview(routing.getBoolean("needsSemanticReview")).build())
                .toolCalls(tools).beforeCanvasHash(hash(before)).afterCanvasHash(hash(after)).build();
    }

    private EvalTrace.TaskOutcome observedOutcome(EvalCaseDefinition evalCase, String routeType,
                                                  boolean runFailed, String before, String after) {
        if (runFailed) return EvalTrace.TaskOutcome.UNKNOWN;
        if (Boolean.TRUE.equals(evalCase.getExpected().getRequireCanvasChange())) {
            return java.util.Objects.equals(hash(before), hash(after))
                    ? EvalTrace.TaskOutcome.NOT_FULFILLED : EvalTrace.TaskOutcome.FULFILLED;
        }
        if ("clarify".equals(routeType)) return EvalTrace.TaskOutcome.CLARIFICATION_NEEDED;
        return EvalTrace.TaskOutcome.FULFILLED;
    }

    private EvalCaseDefinition.ExecutionProfile profile(EvalCaseDefinition evalCase) {
        return evalCase.getExecutionProfile() == null ? new EvalCaseDefinition.ExecutionProfile() : evalCase.getExecutionProfile();
    }
    private boolean failed(String status) { return "FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status); }
    private String hash(String value) { return value == null ? null : contentHasher.hash(value); }
    private void cleanupCanvas(String userId, String diagramId) {
        try {
            canvasStateStore.softDelete(userId, diagramId);
        } catch (RuntimeException e) {
            // Cleanup failure must be visible without replacing the evaluation verdict.
            log.warn("[live-eval] temporary canvas cleanup failed errorClass={}", e.getClass().getSimpleName());
        }
    }
    private double estimatedCost(EvalCaseDefinition.ExecutionProfile profile, long inputTokens, long outputTokens) {
        double inputPrice = profile.getInputPricePerMillion() == null ? 0D : profile.getInputPricePerMillion();
        double outputPrice = profile.getOutputPricePerMillion() == null ? 0D : profile.getOutputPricePerMillion();
        return inputTokens * inputPrice / 1_000_000D + outputTokens * outputPrice / 1_000_000D;
    }
    private boolean isTransient(Throwable error) {
        String message = StringUtils.defaultString(error.getMessage()).toLowerCase();
        return message.contains("429") || message.contains("timeout") || message.contains("temporar") || message.contains("503");
    }

    /** Captures only user-facing text and correlation metadata; the canonical artifact comes from CanvasStateStore. */
    private static final class CapturingEmitter extends ResponseBodyEmitter {
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final List<String> responseParts = new ArrayList<>();
        private volatile String runId;

        private CapturingEmitter(long timeoutMs) { super(timeoutMs); }

        @Override public synchronized void send(Object object) throws IOException {
            capture(object);
            super.send(object);
        }

        @Override public synchronized void complete() {
            super.complete();
            completed.countDown();
        }

        @Override public synchronized void completeWithError(Throwable ex) {
            error.compareAndSet(null, ex);
            super.completeWithError(ex);
            completed.countDown();
        }

        private void awaitCompletion() {
            try {
                if (!completed.await(getTimeout() == null ? 120_000L : getTimeout(), TimeUnit.MILLISECONDS)) {
                    throw new EvalInfrastructureException("live evaluation stream timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EvalInfrastructureException("live evaluation stream interrupted", e);
            }
        }

        private void capture(Object value) {
            if (!(value instanceof String text)) return;
            for (String line : text.split("\\R")) {
                try {
                    JSONObject envelope = JSON.parseObject(line);
                    JSONObject chunk = envelope == null ? null : envelope.getJSONObject("chunk");
                    if (chunk == null) continue;
                    if ("meta".equals(chunk.getString("type"))) runId = chunk.getString("runId");
                    if ("user".equals(chunk.getString("type")) || "token".equals(chunk.getString("type"))) {
                        String content = chunk.getString("content");
                        if (StringUtils.isNotBlank(content)) responseParts.add(content);
                    }
                } catch (RuntimeException ignored) {
                    // Stream status lines are intentionally ignored by the semantic Judge input.
                }
            }
        }

        private String runId() { return runId; }
        private Throwable error() { return error.get(); }
        private String responseText() { return String.join("\n", responseParts); }
    }
}
