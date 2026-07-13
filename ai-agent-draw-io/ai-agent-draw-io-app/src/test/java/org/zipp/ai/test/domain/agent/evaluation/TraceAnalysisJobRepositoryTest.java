package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceAnalysisItem;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceAnalysisJob;
import org.zipp.ai.infrastructure.adapter.repository.evaluation.TraceAnalysisJobRepository;
import org.zipp.ai.infrastructure.dao.ITraceAnalysisMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.TraceAnalysisItemPO;
import org.zipp.ai.infrastructure.dao.po.evaluation.TraceAnalysisJobPO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TraceAnalysisJobRepositoryTest {

    @Test
    public void repositoryRoundTripsProvenanceOutcomeAndAtomicIdempotency() {
        FakeMapper mapper = new FakeMapper();
        TraceAnalysisJobRepository repository = new TraceAnalysisJobRepository(mapper);
        TraceAnalysisJob job = TraceAnalysisJob.builder().id("taj-1").scope("SINGLE_TRACE")
                .analyzerType("LLM").analyzerVersion("model=m1;prompt=p2;schema=s1")
                .analyzerConfigHash("hash").traceSnapshotAt(Instant.parse("2026-07-13T00:00:00Z"))
                .idempotencyKey("idem").status("QUEUED").totalItems(1).reservedCost(0.01D)
                .createdBy("admin").createdAt(Instant.parse("2026-07-13T00:00:00Z")).build();
        TraceAnalysisItem item = TraceAnalysisItem.builder().id("tai-1").jobId("taj-1")
                .sourceRunId("run-1").analyzerType("LLM").status("SUCCEEDED")
                .outcomeStatus("NO_FINDING").attempt(1).estimatedCost(0.01D).build();

        TraceAnalysisJob inserted = repository.insertIfAbsent(job, List.of(item));
        TraceAnalysisJob duplicate = repository.insertIfAbsent(TraceAnalysisJob.builder().id("taj-2")
                .idempotencyKey("idem").build(), List.of());

        assertEquals("taj-1", inserted.getId());
        assertEquals("taj-1", duplicate.getId());
        assertEquals("model=m1;prompt=p2;schema=s1", repository.findJob("taj-1").orElseThrow().getAnalyzerVersion());
        assertEquals("NO_FINDING", repository.listItems("taj-1").get(0).getOutcomeStatus());
        assertEquals(1, mapper.items.size());
    }

    private static final class FakeMapper implements ITraceAnalysisMapper {
        private TraceAnalysisJobPO job;
        private final List<TraceAnalysisItemPO> items = new ArrayList<>();
        @Override public TraceAnalysisJobPO selectJob(String jobId) { return job != null && jobId.equals(job.getId()) ? job : null; }
        @Override public TraceAnalysisJobPO selectJobByIdempotencyKey(String key) { return job != null && key.equals(job.getIdempotencyKey()) ? job : null; }
        @Override public List<TraceAnalysisJobPO> selectJobs(int limit) { return job == null ? List.of() : List.of(job); }
        @Override public List<TraceAnalysisItemPO> selectItems(String jobId) { return items; }
        @Override public int insertJobIfAbsent(TraceAnalysisJobPO value) {
            if (job != null && job.getIdempotencyKey().equals(value.getIdempotencyKey())) return 0;
            job = value; return 1;
        }
        @Override public int insertItem(TraceAnalysisItemPO value) { items.add(value); return 1; }
        @Override public int updateJob(TraceAnalysisJobPO value) { job = value; return 1; }
        @Override public int updateItem(TraceAnalysisItemPO value) { return 1; }
    }
}
