package org.zipp.ai.infrastructure.adapter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;
import org.zipp.ai.application.memory.ConfirmedMemory;
import org.zipp.ai.application.memory.MemoryCandidateFence;
import org.zipp.ai.application.memory.MemoryCandidateProposal;
import org.zipp.ai.application.memory.MemoryCandidateStatus;
import org.zipp.ai.application.memory.MemoryCandidateStorePort;
import org.zipp.ai.application.memory.MemoryMaterializeCommand;
import org.zipp.ai.application.memory.MemoryMaterializeOutcome;
import org.zipp.ai.application.memory.MemoryProposalOutcome;
import org.zipp.ai.application.memory.MemoryProposalService;
import org.zipp.ai.application.memory.SanitizedMemoryProposal;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.ExplicitMemoryDecision;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.NoClarificationReply;
import org.zipp.ai.application.turn.TurnDeclarations;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlCompletedTurnMemoryProposalWriterTest {

    @Test
    void writesCandidateFromPinnedDeclarationWithoutRerunningExtractor() {
        ExplicitMemoryDecision decision = ExplicitMemoryDecision.fromUserContent(
                "remember this decision: use event naming", "chartbook-1").orElseThrow();
        String payload = new TurnInputBindingJsonCodec().encode(new TurnDeclarations(
                List.of(), new NoClarificationReply(), List.of(), decision.declaration()));
        RecordingStore store = new RecordingStore();
        MySqlCompletedTurnMemoryProposalWriter writer =
                new MySqlCompletedTurnMemoryProposalWriter(
                        jdbcReturning(payload), Optional.of(new MemoryProposalService(store)));

        writer.write(attempt(), "diagram-1");

        assertEquals("use event naming", store.proposal.canonicalText());
        assertEquals(decision.declarationDigest(), store.proposal.declarationDigest());
        assertEquals("chartbook-1", store.proposal.chartbookId());
    }

    @Test
    void transientProposalFailureEscapesSoOuterTerminalTransactionCanRollback() {
        ExplicitMemoryDecision decision = ExplicitMemoryDecision.fromUserContent(
                "记住这个决定：使用事件命名", "chartbook-1").orElseThrow();
        String payload = new TurnInputBindingJsonCodec().encode(new TurnDeclarations(
                List.of(), new NoClarificationReply(), List.of(), decision.declaration()));
        MemoryCandidateStorePort failing = new RecordingStore() {
            @Override
            public MemoryProposalOutcome propose(SanitizedMemoryProposal proposal) {
                throw new TransientDataAccessResourceException("database unavailable");
            }
        };
        MySqlCompletedTurnMemoryProposalWriter writer =
                new MySqlCompletedTurnMemoryProposalWriter(
                        jdbcReturning(payload), Optional.of(new MemoryProposalService(failing)));

        assertThrows(TransientDataAccessResourceException.class,
                () -> writer.write(attempt(), "diagram-1"));
    }

    @Test
    void skipsDatabaseWorkWhenMemoryProposalFeatureIsDisabled() {
        MySqlCompletedTurnMemoryProposalWriter writer =
                new MySqlCompletedTurnMemoryProposalWriter(jdbcReturning("unused"), Optional.empty());

        writer.write(attempt(), "diagram-1");
    }

    private FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                new AttemptLease("attempt-1", 1, Instant.now(), 30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(
                        1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }

    private JdbcOperations jdbcReturning(String payload) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (!"query".equals(method.getName())) {
                return defaultValue(method.getReturnType());
            }
            @SuppressWarnings("unchecked")
            RowMapper<String> mapper = (RowMapper<String>) args[1];
            ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    (ignored, resultMethod, resultArgs) ->
                            "getString".equals(resultMethod.getName())
                                    ? payload : defaultValue(resultMethod.getReturnType()));
            return List.of(mapper.mapRow(resultSet, 0));
        };
        return (JdbcOperations) Proxy.newProxyInstance(
                JdbcOperations.class.getClassLoader(),
                new Class<?>[]{JdbcOperations.class},
                handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    private static class RecordingStore implements MemoryCandidateStorePort {
        private SanitizedMemoryProposal proposal;

        @Override
        public MemoryProposalOutcome propose(SanitizedMemoryProposal proposal) {
            this.proposal = proposal;
            return new MemoryProposalOutcome.Accepted(new MemoryCandidateProposal(
                    proposal.candidateId(), proposal.turn(), proposal.chartbookId(),
                    proposal.diagramId(), proposal.decisionKey(), proposal.applicabilityStage(),
                    proposal.scope(), proposal.canonicalText(), proposal.policyVersion(),
                    proposal.declarationDigest(), MemoryCandidateStatus.PENDING, 1,
                    Instant.now().plus(proposal.ttl()), Instant.now().plus(proposal.ttl()),
                    null, null));
        }

        @Override
        public List<MemoryCandidateProposal> listPending(String ownerKey, String chartbookId) {
            return List.of();
        }

        @Override
        public MemoryMaterializeOutcome materialize(MemoryMaterializeCommand command) {
            return null;
        }

        @Override
        public MemoryMaterializeOutcome revoke(MemoryCandidateFence fence) {
            return null;
        }

        @Override
        public MemoryMaterializeOutcome delete(MemoryCandidateFence fence) {
            return null;
        }

        @Override
        public List<ConfirmedMemory> recall(String ownerKey, String chartbookId, int limit) {
            return List.of();
        }
    }
}
