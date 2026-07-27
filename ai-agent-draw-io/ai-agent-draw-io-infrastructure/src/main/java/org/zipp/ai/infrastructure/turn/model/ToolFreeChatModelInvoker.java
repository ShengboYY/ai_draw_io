package org.zipp.ai.infrastructure.turn.model;

import org.zipp.ai.domain.agent.model.entity.ChatCommandEntity;
import org.zipp.ai.domain.agent.service.IChatService;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Invokes a fixed, fresh-session, tool-free agent. The generic legacy runtime is never exposed
 * to the application V2 ports as a mutable tool registry.
 */
public final class ToolFreeChatModelInvoker {

    private final IChatService chat;
    private final String agentId;
    private final String systemUserId;

    public ToolFreeChatModelInvoker(IChatService chat, String agentId, String systemUserId) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.agentId = required(agentId, "agentId");
        this.systemUserId = required(systemUserId, "systemUserId");
        if (!chat.isAgentToolFree(this.agentId)) {
            throw new IllegalArgumentException("configured V2 model agent must be tool-free");
        }
    }

    public String invoke(ModelInputBinding binding, String renderedInput) {
        return invoke(binding, renderedInput, CancellationSignal.NEVER);
    }

    public String invoke(
            ModelInputBinding binding,
            String renderedInput,
            CancellationSignal cancellation
    ) {
        Objects.requireNonNull(binding, "binding");
        cancellation = cancellation == null ? CancellationSignal.NEVER : cancellation;
        requireActive(cancellation);
        String request = binding.envelope(required(renderedInput, "renderedInput"));
        String sessionId = chat.createSession(agentId, systemUserId);
        requireActive(cancellation);
        ChatCommandEntity command = ChatCommandEntity.builder()
                .agentId(agentId)
                .userId(systemUserId)
                .sessionId(sessionId)
                .texts(List.of(new ChatCommandEntity.Content.Text(request)))
                .files(List.of())
                .inlineDatas(List.of())
                .build();
        List<String> replies;
        // Label the model span with the capability that asked for it; otherwise every V2 call is
        // recorded under the single enclosing execution step and the trace cannot be read back.
        try (AgentUsageTelemetryContext.Scope ignored =
                     AgentUsageTelemetryContext.enterPhase(systemUserId)) {
            replies = chat.handleMessage(command);
        } catch (RuntimeException failure) {
            if (cancellation.isCancelled() || causedByCancellation(failure)) {
                CancellationException cancelled =
                        new CancellationException("TURN_EXECUTION_CANCELLED");
                cancelled.initCause(failure);
                throw cancelled;
            }
            throw failure;
        }
        // The blocking chat call runs on the attempt thread and is interrupted on cancellation;
        // this check also discards output from providers that swallow interruption.
        requireActive(cancellation);
        if (replies == null || replies.isEmpty()) {
            throw new IllegalStateException("V2 model returned no output");
        }
        String output = replies.get(replies.size() - 1);
        return required(output, "model output");
    }

    private static void requireActive(CancellationSignal cancellation) {
        if (cancellation.isCancelled()) {
            throw new CancellationException("TURN_EXECUTION_CANCELLED");
        }
    }

    private static boolean causedByCancellation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CancellationException || current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
