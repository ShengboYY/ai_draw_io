package org.zipp.ai.infrastructure.repository;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.citation.answer.AnswerSupportType;
import org.zipp.ai.domain.citation.answer.EvidenceAnswerCommitPort;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.infrastructure.adapter.repository.MySqlEvidenceAnswerCommitAdapter;
import org.zipp.ai.infrastructure.dao.grounding.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlEvidenceAnswerCommitAdapterTest {
    @Test
    void locksCanvasWithoutUpdatingItAndCommitsMessageCitationsPinsAndRun() {
        FakeMapper mapper = new FakeMapper();
        MySqlEvidenceAnswerCommitAdapter adapter = new MySqlEvidenceAnswerCommitAdapter(mapper);
        EvidenceAnswerCommitPort.CommitPlan plan = new EvidenceAnswerCommitPort.CommitPlan(
                new CatalogOwner(OwnerType.USER, "alice"), "diagram-1", "session-1", "message-1",
                "request-1", "run-1", 7L, "hash-7", 1L, "- supported [C1]",
                List.of(new EvidenceAnswerCommitPort.CitationWrite("citation-1", "C1",
                        AnswerSupportType.DIRECT, "semantic-hash",
                        List.of(new EvidenceAnswerCommitPort.EvidenceLink("E1", "evidence-1", "material-1",
                                "version-1", "revision-1", "SUPPORT", "SEARCH")))));

        assertEquals(EvidenceAnswerCommitPort.CommitStatus.COMMITTED, adapter.commit(plan));
        assertEquals(1, mapper.messageWrites.get());
        assertEquals(1, mapper.citationWrites.get());
        assertEquals(1, mapper.evidenceWrites.get());
        assertEquals(1, mapper.pinWrites.get());
        assertEquals(1, mapper.runCompletions.get());
    }

    @Test
    void rejectsAChangedCanvasTupleBeforeAnyAnswerWrite() {
        FakeMapper mapper = new FakeMapper();
        mapper.canvasVersion = 8L;

        assertThrows(IllegalStateException.class,
                () -> new MySqlEvidenceAnswerCommitAdapter(mapper).commit(plan()));

        assertEquals(0, mapper.messageWrites.get());
        assertEquals(0, mapper.citationWrites.get());
    }

    @Test
    void rejectsExpiredEvidenceLeaseInsideTheAnswerTransaction() {
        FakeMapper mapper = new FakeMapper();
        mapper.validEvidenceLink = 0;

        assertThrows(IllegalStateException.class,
                () -> new MySqlEvidenceAnswerCommitAdapter(mapper).commit(plan()));

        assertEquals(0, mapper.evidenceWrites.get());
        assertEquals(0, mapper.runCompletions.get());
    }

    @Test
    void commitAndCancellationBarrierAllowsOnlyTheRowLockWinner() throws Exception {
        BarrierMapper mapper = new BarrierMapper();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<EvidenceAnswerCommitPort.CommitStatus> commit = executor.submit(
                    () -> new MySqlEvidenceAnswerCommitAdapter(mapper).commit(plan()));
            assertEquals(true, mapper.commitLocked.await(1, TimeUnit.SECONDS));
            Future<String> cancellation = executor.submit(mapper::cancel);
            assertEquals(true, mapper.cancelStarted.await(1, TimeUnit.SECONDS));

            mapper.allowCommit.countDown();

            assertEquals(EvidenceAnswerCommitPort.CommitStatus.COMMITTED, commit.get(1, TimeUnit.SECONDS));
            assertEquals("ALREADY_COMPLETED", cancellation.get(1, TimeUnit.SECONDS));
            assertEquals("COMPLETED", mapper.state);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationWinnerPreventsLateAnswerCommit() {
        BarrierMapper mapper = new BarrierMapper();
        assertEquals("CANCELLED", mapper.cancel());

        assertThrows(IllegalStateException.class,
                () -> new MySqlEvidenceAnswerCommitAdapter(mapper).commit(plan()));
        assertEquals(0, mapper.messageWrites.get());
    }

    private EvidenceAnswerCommitPort.CommitPlan plan() {
        return new EvidenceAnswerCommitPort.CommitPlan(new CatalogOwner(OwnerType.USER, "alice"),
                "diagram-1", "session-1", "message-1", "request-1", "run-1", 7L, "hash-7", 1L,
                "- supported [C1]", List.of(new EvidenceAnswerCommitPort.CitationWrite(
                "citation-1", "C1", AnswerSupportType.DIRECT, "semantic-hash",
                List.of(new EvidenceAnswerCommitPort.EvidenceLink("E1", "evidence-1", "material-1",
                        "version-1", "revision-1", "SUPPORT", "SEARCH")))));
    }

    private static class FakeMapper implements IEvidenceAnswerCommitMapper {
        protected final AtomicInteger messageWrites = new AtomicInteger();
        protected final AtomicInteger citationWrites = new AtomicInteger();
        protected final AtomicInteger evidenceWrites = new AtomicInteger();
        protected final AtomicInteger pinWrites = new AtomicInteger();
        protected final AtomicInteger runCompletions = new AtomicInteger();
        private long canvasVersion = 7L;
        private int validEvidenceLink = 1;

        @Override public GroundedRunRowPO lockRunState(EvidenceAnswerCommitPort.CommitPlan plan) {
            GroundedRunRowPO row = new GroundedRunRowPO();
            row.setState("RUNNING");
            row.setGeneration(1L);
            return row;
        }
        @Override public AnswerCanvasTupleRowPO lockCanvasTuple(EvidenceAnswerCommitPort.CommitPlan plan) {
            AnswerCanvasTupleRowPO row = new AnswerCanvasTupleRowPO();
            row.setVersion(canvasVersion);
            row.setContentHash("hash-7");
            return row;
        }
        @Override public int insertMessage(EvidenceAnswerCommitPort.CommitPlan plan) { messageWrites.incrementAndGet(); return 1; }
        @Override public int insertCitation(EvidenceAnswerCommitPort.CommitPlan plan,
                                            EvidenceAnswerCommitPort.CitationWrite citation, long canvasVersion) {
            citationWrites.incrementAndGet(); return 1;
        }
        @Override public int insertCitationEvidence(String citationId, EvidenceAnswerCommitPort.EvidenceLink link) {
            evidenceWrites.incrementAndGet(); return 1;
        }
        @Override public String lockValidEvidenceLink(EvidenceAnswerCommitPort.CommitPlan plan,
                                                      EvidenceAnswerCommitPort.EvidenceLink link) {
            return validEvidenceLink == 1 ? link.evidenceId() : null;
        }
        @Override public int upsertSourcePin(EvidenceAnswerCommitPort.CommitPlan plan,
                                             EvidenceAnswerCommitPort.EvidenceLink link) {
            pinWrites.incrementAndGet(); return 1;
        }
        @Override public int completeRun(EvidenceAnswerCommitPort.CommitPlan plan) {
            runCompletions.incrementAndGet(); return 1;
        }
    }

    private static final class BarrierMapper extends FakeMapper {
        private final ReentrantLock runLock = new ReentrantLock();
        private final CountDownLatch commitLocked = new CountDownLatch(1);
        private final CountDownLatch cancelStarted = new CountDownLatch(1);
        private final CountDownLatch allowCommit = new CountDownLatch(1);
        private volatile String state = "RUNNING";
        private volatile long generation = 1L;

        @Override
        public GroundedRunRowPO lockRunState(EvidenceAnswerCommitPort.CommitPlan plan) {
            runLock.lock();
            commitLocked.countDown();
            GroundedRunRowPO row = new GroundedRunRowPO();
            row.setState(state);
            row.setGeneration(generation);
            if ("RUNNING".equals(state)) await(allowCommit);
            else runLock.unlock();
            return row;
        }

        @Override
        public int completeRun(EvidenceAnswerCommitPort.CommitPlan plan) {
            state = "COMPLETED";
            int changed = super.completeRun(plan);
            runLock.unlock();
            return changed;
        }

        private String cancel() {
            cancelStarted.countDown();
            runLock.lock();
            try {
                if (!"RUNNING".equals(state)) return "ALREADY_COMPLETED";
                state = "CANCELLED";
                generation++;
                return "CANCELLED";
            } finally {
                runLock.unlock();
            }
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(1, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }
}
