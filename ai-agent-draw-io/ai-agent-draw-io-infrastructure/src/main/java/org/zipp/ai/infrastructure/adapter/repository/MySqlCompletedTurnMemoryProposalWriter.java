package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;
import org.zipp.ai.application.memory.MemoryProposalCommand;
import org.zipp.ai.application.memory.MemoryProposalOutcome;
import org.zipp.ai.application.memory.MemoryProposalService;
import org.zipp.ai.application.turn.ExplicitMemoryDecision;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.RememberDecisionDeclaration;
import org.zipp.ai.application.turn.TurnDeclarations;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes the pending Memory proposal inside the successful terminal transaction.
 *
 * <p>The first claim's input binding is the only source. Database or process failure therefore
 * rolls back both terminal completion and the proposal, instead of losing a best-effort hook.</p>
 */
@Component
public final class MySqlCompletedTurnMemoryProposalWriter {

    private static final String SELECT_INPUT_BINDING = """
            SELECT turn_input_binding_json
            FROM turn_execution
            WHERE owner_key = ? AND conversation_id = ? AND turn_id = ?
              AND status = 'COMPLETED'
            """;

    private final JdbcOperations jdbc;
    private final Optional<MemoryProposalService> proposals;
    private final TurnInputBindingJsonCodec inputCodec = new TurnInputBindingJsonCodec();

    public MySqlCompletedTurnMemoryProposalWriter(
            JdbcOperations jdbc,
            Optional<MemoryProposalService> proposals
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.proposals = Objects.requireNonNull(proposals, "proposals");
    }

    public void write(FencedAttempt attempt, String diagramId) {
        Objects.requireNonNull(attempt, "attempt");
        if (diagramId == null || diagramId.isBlank()) {
            throw new IllegalArgumentException("diagramId must not be blank");
        }
        // Memory proposals share the catalog rollout gate; ordinary turns still commit while it is off.
        if (proposals.isEmpty()) {
            return;
        }
        List<String> payloads = jdbc.query(
                SELECT_INPUT_BINDING,
                (resultSet, rowNum) -> resultSet.getString("turn_input_binding_json"),
                attempt.key().ownerKey(),
                attempt.key().canonicalConversationId(),
                attempt.key().turnId());
        if (payloads.size() != 1) {
            throw new IllegalStateException("MEMORY_TERMINAL_INPUT_BINDING_UNAVAILABLE");
        }
        TurnDeclarations declarations = inputCodec.decode(payloads.get(0));
        if (!(declarations.memoryWrite() instanceof RememberDecisionDeclaration declaration)) {
            return;
        }
        if (!declaration.hasPinnedProposal()) {
            throw new IllegalStateException("MEMORY_PINNED_PROPOSAL_UNAVAILABLE");
        }
        MemoryProposalOutcome outcome = proposals.orElseThrow().propose(new MemoryProposalCommand(
                attempt.key(),
                declaration.chartbookId(),
                diagramId,
                ExplicitMemoryDecision.candidateId(
                        attempt.key(), declaration.digest().value()),
                declaration.digest().value(),
                declaration,
                declaration.decisionKey(),
                declaration.applicabilityStage(),
                declaration.canonicalText()));
        if (outcome instanceof MemoryProposalOutcome.Rejected rejected) {
            // Throwing here rolls the outer terminal transaction back.
            throw new IllegalStateException(rejected.code());
        }
    }
}
