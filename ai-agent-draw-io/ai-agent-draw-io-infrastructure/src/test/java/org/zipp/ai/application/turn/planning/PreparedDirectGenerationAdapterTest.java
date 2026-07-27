package org.zipp.ai.application.turn.planning;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.DirectGenerationPort;
import org.zipp.ai.application.turn.DirectVisionPort;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.ConversationContext;
import org.zipp.ai.application.turn.context.CurrentMessageAttachmentsContext;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.context.TrustedCanvasContext;
import org.zipp.ai.application.turn.context.ValidatedSelectionContext;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.infrastructure.adapter.repository.MySqlDirectPreparationStore;
import org.zipp.ai.infrastructure.turn.model.PreparedDirectGenerationAdapter;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreparedDirectGenerationAdapterTest {

    @Test
    void returnsTheVerifiedPreparedProjectionWithoutCallingAModel() {
        StubPreparationStore preparations = new StubPreparationStore(
                new MySqlDirectPreparationStore.Prepared(
                        "prepared-1", "e".repeat(64), "snapshot-1",
                        "observation-fingerprint", "<mxGraphModel><root/></mxGraphModel>"));

        DirectGenerationPort.Result result =
                new PreparedDirectGenerationAdapter(preparations).generate(
                        request(), CancellationSignal.NEVER);

        assertEquals("prepared-1", result.payloadRef());
        assertEquals("<mxGraphModel><root/></mxGraphModel>", result.canvasXml());
        assertEquals(
                "Reconstructed the attached image as an editable diagram. "
                        + "Please review any unclear text or connectors.",
                result.assistantMessage());
        assertEquals(1, preparations.findCalls);
    }

    @Test
    void cancellationStopsBeforeReadingThePreparedProjection() {
        StubPreparationStore preparations = new StubPreparationStore(null);
        PreparedDirectGenerationAdapter adapter = new PreparedDirectGenerationAdapter(preparations);

        assertThrows(CancellationException.class, () ->
                adapter.generate(request(), () -> true));

        assertEquals(0, preparations.findCalls);
    }

    private DirectGenerationPort.Request request() {
        return new DirectGenerationPort.Request(
                attempt(),
                context(),
                readSet(),
                directPlan(),
                new DirectVisionPort.Observation("prepared-1", "observation-fingerprint"));
    }

    private FencedAttempt attempt() {
        return new FencedAttempt(
                new TurnKey("owner-1", "conversation-1", "turn-1"),
                AttemptLease.fromDatabaseClock(
                        "attempt-1", 1,
                        Instant.parse("2026-07-26T00:00:00Z"),
                        Instant.parse("2026-07-26T00:00:30Z"),
                        30_000),
                2,
                "input-digest",
                new ExecutionPolicySnapshot(1, TurnEngineMode.V2_CANARY, "{}", "policy"));
    }

    private BoundSourcePlan directPlan() {
        TurnKey turn = new TurnKey("owner-1", "conversation-1", "turn-1");
        SourceProbeBinding probe = new SourceProbeBinding(
                turn,
                new PlanningLineageFingerprint("a".repeat(64)),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64));
        DirectSelector selector = new DirectSelector(new DirectCandidateFact(
                probe,
                "candidate-1",
                DirectCandidateOrigin.CURRENT_MESSAGE_ATTACHMENT,
                "observation-1",
                "clarification-ref-1"));
        return new BoundSourcePlan(
                new SourceAwareDrawPlan.Direct(selector),
                new SourcePlanIdentity(probe.lineage(), "e".repeat(64)),
                new SourceExecutionEntry.Primary());
    }

    private BaseTurnContext context() {
        return new BaseTurnContext(
                new CurrentRequestContext(
                        "turn-1", "diagram-1", new CurrentInstruction("reconstruct")),
                new AvailableContext<>(
                        new CurrentMessageAttachmentsContext("binding-1", List.of()),
                        "attachments"),
                new AbsentContext<>("no clarification"),
                new AvailableContext<>(
                        new TrustedCanvasContext(false, 0, 0, ""), "canvas"),
                new AvailableContext<>(
                        new ValidatedSelectionContext(false, 0), "selection"),
                new AvailableContext<>(
                        new ConversationContext(List.of(), ""), "conversation"),
                new AbsentContext<>("no membership"),
                new AbsentContext<>("no profile"),
                new AbsentContext<>("no memory"),
                new ContextDiagnostics(List.of()));
    }

    private ContextReadSet readSet() {
        return ContextReadSet.create(
                1,
                2,
                ContextSlicePin.absent(ContextSlice.SUMMARY, "NO_CANVAS"),
                ContextSlicePin.absent(ContextSlice.MEMBERSHIP, "NO_ACTIVE_CHARTBOOK"),
                ContextSlicePin.absent(ContextSlice.PROFILE, "PROFILE_NOT_AVAILABLE"),
                ContextSlicePin.absent(ContextSlice.MEMORY, "NO_CONFIRMED_MEMORY"));
    }

    private static final class StubPreparationStore extends MySqlDirectPreparationStore {
        private final Prepared prepared;
        private int findCalls;

        private StubPreparationStore(Prepared prepared) {
            super(unusedJdbc());
            this.prepared = prepared;
        }

        @Override
        public Optional<Prepared> find(FencedAttempt attempt, String preparedRef) {
            findCalls++;
            return Optional.ofNullable(prepared);
        }

        private static JdbcOperations unusedJdbc() {
            return (JdbcOperations) Proxy.newProxyInstance(
                    JdbcOperations.class.getClassLoader(),
                    new Class<?>[]{JdbcOperations.class},
                    (proxy, method, arguments) -> {
                        throw new AssertionError("JDBC must not be used by the stub preparation store");
                    });
        }
    }
}
