package org.zipp.ai.infrastructure.turn.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zipp.ai.application.turn.PlainGenerationPort;
import org.zipp.ai.application.turn.PlainGenerationRequest;
import org.zipp.ai.application.turn.PlainGenerationResult;
import org.zipp.ai.application.turn.TurnEvent;
import org.zipp.ai.application.turn.TurnEventSink;
import org.zipp.ai.application.turn.agent.BoundedDiagramAgentRuntime;
import org.zipp.ai.application.turn.skill.DiagramSkillBundle;
import org.zipp.ai.application.turn.skill.DiagramSkillContentPort;
import org.zipp.ai.domain.retrieval.CancellationSignal;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Adapts the bounded working-copy result back to the unchanged Plain commit contract. */
public final class AgenticPlainGenerationAdapter implements PlainGenerationPort {

    private static final Logger LOG =
            LoggerFactory.getLogger(AgenticPlainGenerationAdapter.class);
    private static final Pattern SAFE_OUTCOME_CODE = Pattern.compile("[A-Z0-9_]{3,80}");

    private final DiagramSkillContentPort skills;
    private final BoundedDiagramAgentRuntime runtime;

    public AgenticPlainGenerationAdapter(
            DiagramSkillContentPort skills,
            BoundedDiagramAgentRuntime runtime
    ) {
        this.skills = Objects.requireNonNull(skills, "skills");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
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
        DiagramSkillBundle bundle;
        try {
            bundle = skills.load(
                    request.attempt().key().ownerKey(),
                    request.plan().skillSelection());
        } catch (RuntimeException failure) {
            // Skill bodies and model input stay out of operational logs; only stable codes are safe.
            LOG.warn("[plain-agent] event=skill_load_failed attemptId={} epoch={} "
                            + "outcome={} errorClass={}",
                    request.attempt().attemptId(),
                    request.attempt().attemptEpoch(),
                    safeOutcome(failure),
                    failure.getClass().getSimpleName());
            throw failure;
        }
        var candidate = runtime.run(request, bundle, cancellation, events);
        events.publish(new TurnEvent(
                "plain_agent_candidate_submitted",
                candidate.draftDigest(),
                Instant.now()));
        String digest = candidate.draftDigest().replaceFirst("^sha256:", "");
        String payloadRef = "plain-agent-" + digest.substring(0, Math.min(24, digest.length()));
        return new PlainGenerationResult(
                payloadRef,
                candidate.canvasXml(),
                candidate.assistantMessage());
    }

    private String safeOutcome(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && SAFE_OUTCOME_CODE.matcher(message).matches()
                ? message
                : "PLAIN_AGENT_SKILL_LOAD_FAILED";
    }
}
