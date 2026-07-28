package org.zipp.ai.infrastructure.turn.agent;

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

/** Adapts the bounded working-copy result back to the unchanged Plain commit contract. */
public final class AgenticPlainGenerationAdapter implements PlainGenerationPort {

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
        DiagramSkillBundle bundle = skills.load(
                request.attempt().key().ownerKey(),
                request.plan().skillSelection());
        var candidate = runtime.run(request, bundle, cancellation);
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
}
