package org.zipp.ai.infrastructure.turn.model;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.turn.context.AutoMemoryContextQuery;
import org.zipp.ai.application.turn.context.AutoMemoryRecallClauseFallback;
import org.zipp.ai.application.turn.context.AutoMemoryRecallPlanner;
import org.zipp.ai.application.turn.context.AutoMemoryRecallPlanningEligibilityPolicy;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Calls the planner only for requests with a plausible multi-intent signal. */
@Component
@ConditionalOnProperty(
        name = {"app.memory.auto-enabled", "app.memory.context.semantic-enabled",
                "app.memory.context.multi-intent-enabled"},
        havingValue = "true")
public final class ChatAutoMemoryRecallPlannerAdapter implements AutoMemoryRecallPlanner {
    private final ToolFreeChatModelInvoker model;
    private final AutoMemoryRecallPlanningEligibilityPolicy eligibility;
    private final AutoMemoryRecallClauseFallback fallback;

    @Autowired
    public ChatAutoMemoryRecallPlannerAdapter(
            org.zipp.ai.domain.agent.service.IChatService chat,
            @Value("${app.memory.context.planner-agent-id:300031}") String agentId
    ) {
        this(new ToolFreeChatModelInvoker(chat, agentId, "auto-memory-recall-planning"),
                new AutoMemoryRecallPlanningEligibilityPolicy());
    }

    ChatAutoMemoryRecallPlannerAdapter(
            ToolFreeChatModelInvoker model,
            AutoMemoryRecallPlanningEligibilityPolicy eligibility
    ) {
        this(model, eligibility, new AutoMemoryRecallClauseFallback());
    }

    ChatAutoMemoryRecallPlannerAdapter(
            ToolFreeChatModelInvoker model,
            AutoMemoryRecallPlanningEligibilityPolicy eligibility,
            AutoMemoryRecallClauseFallback fallback
    ) {
        this.model = Objects.requireNonNull(model, "model");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    @Override
    public List<String> plan(AutoMemoryContextQuery query) {
        Objects.requireNonNull(query, "query");
        if (!eligibility.shouldPlan(query.userContent())) {
            return List.of();
        }
        try {
            String output = model.invoke(
                    query.planningInputBinding(), AutoMemoryRecallPlanningProtocol.render(query));
            List<String> planned = AutoMemoryRecallPlanningProtocol.parse(output);
            return planned.isEmpty() ? fallback.facets(query.userContent()) : planned;
        } catch (RuntimeException failure) {
            if (failure instanceof CancellationException) {
                throw failure;
            }
            List<String> recovered = fallback.facets(query.userContent());
            if (!recovered.isEmpty()) {
                return recovered;
            }
            throw failure;
        }
    }
}
