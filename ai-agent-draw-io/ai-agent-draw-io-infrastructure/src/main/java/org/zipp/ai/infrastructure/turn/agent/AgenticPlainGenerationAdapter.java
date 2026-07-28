package org.zipp.ai.infrastructure.turn.agent;

import org.zipp.ai.application.turn.PlainGenerationPort;
import org.zipp.ai.application.turn.PlainGenerationRequest;
import org.zipp.ai.application.turn.PlainGenerationResult;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.agent.BoundedDiagramAgentRuntime;
import org.zipp.ai.application.turn.agent.PlainAgentTraceEvent;
import org.zipp.ai.application.turn.agent.PlainAgentTracePort;
import org.zipp.ai.application.turn.agent.PlainAgentTraceType;
import org.zipp.ai.application.turn.skill.DiagramSkillBundle;
import org.zipp.ai.application.turn.skill.DiagramSkillContentPort;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Adapts the bounded working-copy result back to the unchanged Plain commit contract. */
public final class AgenticPlainGenerationAdapter implements PlainGenerationPort {

    private static final Pattern SAFE_OUTCOME_CODE = Pattern.compile("[A-Z0-9_]{3,80}");

    private final DiagramSkillContentPort skills;
    private final BoundedDiagramAgentRuntime runtime;
    private final PlainAgentTracePort trace;

    public AgenticPlainGenerationAdapter(
            DiagramSkillContentPort skills,
            BoundedDiagramAgentRuntime runtime
    ) {
        this(skills, runtime, PlainAgentTracePort.NOOP);
    }

    public AgenticPlainGenerationAdapter(
            DiagramSkillContentPort skills,
            BoundedDiagramAgentRuntime runtime,
            PlainAgentTracePort trace
    ) {
        this.skills = Objects.requireNonNull(skills, "skills");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    @Override
    public PlainGenerationResult generate(
            PlainGenerationRequest request,
            TurnEventSink events,
            CancellationSignal cancellation
    ) {
        if (request == null || events == null) {
            throw new IllegalArgumentException("plain agent request and events are required");
        }
        String bindingDigest = request.plan().skillSelection().bindingDigest();
        long skillLoadStarted = System.nanoTime();
        trace(request, PlainAgentTraceType.SKILL_LOADING_STARTED,
                "STARTED", bindingDigest, 0);
        DiagramSkillBundle bundle;
        try {
            bundle = skills.load(
                    request.attempt().key().ownerKey(),
                    request.plan().skillSelection());
        } catch (RuntimeException failure) {
            trace(request, PlainAgentTraceType.SKILLS_LOADED,
                    safeOutcome(failure), bindingDigest, elapsedMillis(skillLoadStarted));
            throw failure;
        }
        trace(request, PlainAgentTraceType.SKILLS_LOADED,
                "SUCCESS", bundle.selectionBindingDigest(), elapsedMillis(skillLoadStarted));
        publish(events, "plain_agent_skills_loaded",
                Integer.toString(bundle.orderedSkills().size()));
        var candidate = runtime.run(request, bundle, cancellation, events);
        String digest = candidate.draftDigest().replaceFirst("^sha256:", "");
        String payloadRef = "plain-agent-" + digest.substring(0, Math.min(24, digest.length()));
        return new PlainGenerationResult(
                payloadRef,
                candidate.canvasXml(),
                candidate.assistantMessage());
    }

    private void trace(
            PlainGenerationRequest request,
            PlainAgentTraceType type,
            String outcome,
            String bindingDigest,
            long latencyMillis
    ) {
        // Skill bodies never enter telemetry; only their immutable selection digest is recorded.
        trace.recordSafely(new PlainAgentTraceEvent(
                request.attempt(),
                0,
                type,
                "LOAD_SKILLS",
                "diagram_skill_content",
                bindingDigest,
                "",
                "",
                outcome,
                0,
                latencyMillis,
                Instant.now()));
    }

    private void publish(TurnEventSink events, String type, String payload) {
        try {
            events.publish(new TurnEvent(type, payload, Instant.now()));
        } catch (RuntimeException ignored) {
            // A detached progress stream must not change the generated candidate.
        }
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private String safeOutcome(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && SAFE_OUTCOME_CODE.matcher(message).matches()
                ? message
                : "PLAIN_AGENT_SKILL_LOAD_FAILED";
    }
}
