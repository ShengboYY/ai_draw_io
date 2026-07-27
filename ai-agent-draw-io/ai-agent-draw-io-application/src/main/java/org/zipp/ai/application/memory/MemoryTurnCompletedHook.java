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
        if (!declaration.hasPinnedProposal()) {
            return;
        }
        // Never rerun language extraction after admission; only the first pinned declaration is
        // authoritative for candidate identity and content.
        proposals.propose(new MemoryProposalCommand(
                attempt.key(), declaration.chartbookId(), command.diagramId(),
                ExplicitMemoryDecision.candidateId(attempt.key(), declaration.digest().value()),
                declaration.digest().value(), declaration,
                declaration.decisionKey(), declaration.applicabilityStage(),
                declaration.canonicalText()));
    }
}
