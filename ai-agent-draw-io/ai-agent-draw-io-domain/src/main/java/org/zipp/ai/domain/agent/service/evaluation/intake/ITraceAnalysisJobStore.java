package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceAnalysisItem;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceAnalysisJob;

import java.util.List;
import java.util.Optional;

/** Persistence port for Analysis Job orchestration; Candidate remains the Finding write model. */
public interface ITraceAnalysisJobStore {
    Optional<TraceAnalysisJob> findJob(String jobId);
    Optional<TraceAnalysisJob> findJobByIdempotencyKey(String idempotencyKey);
    List<TraceAnalysisJob> listJobs(int limit);
    List<TraceAnalysisItem> listItems(String jobId);
    /** Atomically inserts the aggregate, or returns the existing aggregate for the same idempotency key. */
    TraceAnalysisJob insertIfAbsent(TraceAnalysisJob job, List<TraceAnalysisItem> items);
    void updateJob(TraceAnalysisJob job);
    void updateItem(TraceAnalysisItem item);
}
