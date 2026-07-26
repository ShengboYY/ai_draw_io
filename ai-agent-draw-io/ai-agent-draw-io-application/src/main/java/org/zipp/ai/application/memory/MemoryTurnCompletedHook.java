package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.ExplicitMemoryDecision;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.RememberDecisionDeclaration;
import org.zipp.ai.application.turn.UserTurnCommand;
import org.zipp.ai.application.turn.execution.TurnCompletedHook;

import java.util.Objects;

/** Creates a pending Memory candidate from the declaration persisted with a completed turn. */
public final class MemoryTurnCompletedHook implements TurnCompletedHook {

    private final MemoryProposalService proposals;

    public MemoryTurnCompletedHook(MemoryProposalService proposals) {
        this.proposals = Objects.requireNonNull(proposals, "proposals");
    }

    @Override
    public void afterCompleted(FencedAttempt attempt, UserTurnCommand command) {
        if (!(command.declarations().memoryWrite() instanceof RememberDecisionDeclaration declaration)) {
            return;
        }
        ExplicitMemoryDecision.fromUserContent(command.content(), declaration.chartbookId())
                .filter(decision -> decision.declaration().equals(declaration))
                .ifPresent(decision -> proposals.propose(new MemoryProposalCommand(
                        attempt.key(), declaration.chartbookId(), command.diagramId(),
                        decision.candidateId(attempt.key()), decision.declarationDigest(), declaration,
                        decision.decisionKey(), decision.applicabilityStage(), decision.canonicalText())));
    }
}
