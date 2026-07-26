package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.classification.OutputIntent;
import org.zipp.ai.application.turn.classification.SemanticAction;
import org.zipp.ai.application.turn.classification.SemanticIntent;
import org.zipp.ai.application.turn.classification.TargetNeed;
import org.zipp.ai.application.turn.demand.AcceptedSourceDemand;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.application.turn.demand.ResolvedSourceDemand;
import org.zipp.ai.application.turn.demand.SourceDemandKind;
import org.zipp.ai.application.turn.planning.RoleAvailability;
import org.zipp.ai.application.turn.planning.SourceAvailability;
import org.zipp.ai.application.turn.planning.SourceProbeCommand;
import org.zipp.ai.application.turn.planning.SourceProbeContext;
import org.zipp.ai.application.turn.planning.SourceProbeOutcome;
import org.zipp.ai.application.turn.planning.SourceRole;
import org.zipp.ai.application.turn.planning.PrePlanOutcome;
import org.zipp.ai.application.turn.planning.PlanningLineageFingerprint;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.RequestSourceOrigin;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionCommand;
import org.zipp.ai.domain.retrieval.ResolvedSource;
import org.zipp.ai.domain.retrieval.ResolvedSourceSet;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionService;
import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MySqlSourceProbeAdapterTest {

    @Test
    void resolvesDirectFactsFromTheServerSnapshotWithoutReturningContent() {
        AtomicReference<RequestSourceResolutionCommand> captured = new AtomicReference<>();
        RequestSourceResolutionService resolution = command -> {
            captured.set(command);
            return new ResolvedSourceSet(SourceMode.EXPLICIT, List.of(
                    source("IMAGE", "image-v1", false, true)), 0, 0);
        };
        SourceProbeCommand command = command(SourceDemandKind.CURRENT_MESSAGE_DIRECT_REQUIRED);

        SourceProbeOutcome.Available outcome = assertInstanceOf(
                SourceProbeOutcome.Available.class,
                new MySqlSourceProbeAdapter(resolution).probe(
                        command, new SourceProbeContext(
                                "USER", "owner-1", "diagram-1", "conversation-1", "turn-1")));

        SourceAvailability.SingleRole availability = assertInstanceOf(
                SourceAvailability.SingleRole.class, outcome.availability());
        RoleAvailability.DirectAvailable direct = assertInstanceOf(
                RoleAvailability.DirectAvailable.class, availability.role());
        assertEquals("image-v1", direct.candidates().get(0).candidateRef());
        assertEquals("owner-1", captured.get().owner().ownerKey());
        assertEquals("diagram-1", captured.get().diagramId());
    }

    @Test
    void returnsRoleSpecificRetrievalFactsForRequiredRetrieval() {
        RequestSourceResolutionService resolution = command -> new ResolvedSourceSet(
                SourceMode.AUTO, List.of(source("PDF", "text-v1", true, false)), 0, 0);
        SourceProbeCommand command = command(SourceDemandKind.CURRENT_MESSAGE_RETRIEVAL_REQUIRED);

        SourceProbeOutcome.Available outcome = assertInstanceOf(
                SourceProbeOutcome.Available.class,
                new MySqlSourceProbeAdapter(resolution).probe(
                        command, new SourceProbeContext(
                                "USER", "owner-1", "diagram-1", "conversation-1", "turn-1")));

        SourceAvailability.SingleRole availability = assertInstanceOf(
                SourceAvailability.SingleRole.class, outcome.availability());
        RoleAvailability.RetrievalAvailable retrieval = assertInstanceOf(
                RoleAvailability.RetrievalAvailable.class, availability.role());
        assertEquals("text-v1", retrieval.candidates().get(0).candidateRef());
        assertEquals(SourceRole.RETRIEVAL, retrieval.role());
    }

    private SourceProbeCommand command(SourceDemandKind kind) {
        AcceptedSourceDemand accepted = new AcceptedSourceDemand(
                kind,
                List.of("upload-1"),
                kind == SourceDemandKind.CURRENT_MESSAGE_RETRIEVAL_REQUIRED ? "find facts" : null);
        PrePlanOutcome.SourcePlanningRequired required = new PrePlanOutcome.SourcePlanningRequired(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new CurrentInstruction("use the source"),
                new SemanticIntent(SemanticAction.CREATE, OutputIntent.DRAWING,
                        TargetNeed.NOT_REQUIRED, "unknown", "none"),
                new ResolvedSourceDemand(accepted, List.of()),
                accepted,
                new PlanningLineageFingerprint("a".repeat(64)),
                "b".repeat(64),
                "c".repeat(64));
        return SourceProbeCommand.from(required);
    }

    private ResolvedSource source(String kind, String version, boolean hasText, boolean hasVisual) {
        return new ResolvedSource(
                "material-1", version, "revision-1", kind, "source",
                MaterialScopeType.CONVERSATION, "conversation-1", "READY",
                RequestSourceOrigin.ATTACHMENT, hasText, hasVisual, false);
    }
}
