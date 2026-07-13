package org.zipp.ai.infrastructure.adapter.repository.evaluation;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceAnalysisItem;
import org.zipp.ai.domain.agent.model.valobj.evaluation.intake.TraceAnalysisJob;
import org.zipp.ai.domain.agent.service.evaluation.intake.ITraceAnalysisJobStore;
import org.zipp.ai.infrastructure.dao.ITraceAnalysisMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.TraceAnalysisItemPO;
import org.zipp.ai.infrastructure.dao.po.evaluation.TraceAnalysisJobPO;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public class TraceAnalysisJobRepository implements ITraceAnalysisJobStore {
    private final ITraceAnalysisMapper mapper;

    public TraceAnalysisJobRepository(ITraceAnalysisMapper mapper) { this.mapper = mapper; }

    @Override public Optional<TraceAnalysisJob> findJob(String id) { return Optional.ofNullable(mapper.selectJob(id)).map(this::job); }
    @Override public Optional<TraceAnalysisJob> findJobByIdempotencyKey(String key) { return Optional.ofNullable(mapper.selectJobByIdempotencyKey(key)).map(this::job); }
    @Override public List<TraceAnalysisJob> listJobs(int limit) { return mapper.selectJobs(limit).stream().map(this::job).toList(); }
    @Override public List<TraceAnalysisItem> listItems(String jobId) { return mapper.selectItems(jobId).stream().map(this::item).toList(); }

    @Override @Transactional
    public TraceAnalysisJob insertIfAbsent(TraceAnalysisJob job, List<TraceAnalysisItem> items) {
        mapper.insertJobIfAbsent(jobPo(job));
        TraceAnalysisJob canonical = findJobByIdempotencyKey(job.getIdempotencyKey())
                .orElseThrow(() -> new IllegalStateException("analysis job idempotency conflict"));
        if (!job.getId().equals(canonical.getId())) return canonical;
        items.forEach(value -> mapper.insertItem(itemPo(value)));
        return job;
    }

    @Override public void updateJob(TraceAnalysisJob job) { mapper.updateJob(jobPo(job)); }
    @Override public void updateItem(TraceAnalysisItem item) { mapper.updateItem(itemPo(item)); }

    private TraceAnalysisJob job(TraceAnalysisJobPO p) {
        return TraceAnalysisJob.builder().id(p.getId()).scope(p.getScope()).analyzerType(p.getAnalyzerType())
                .analyzerVersion(p.getAnalyzerVersion())
                .analyzerConfigHash(p.getAnalyzerConfigHash()).sampleDefinitionJson(p.getSampleDefinitionJson())
                .traceSnapshotAt(instant(p.getTraceSnapshotAt())).idempotencyKey(p.getIdempotencyKey()).status(p.getStatus())
                .totalItems(value(p.getTotalItems())).succeededItems(value(p.getSucceededItems())).failedItems(value(p.getFailedItems()))
                .reservedCost(value(p.getReservedCost())).actualCost(value(p.getActualCost())).createdBy(p.getCreatedBy())
                .createdAt(instant(p.getCreatedAt())).startedAt(instant(p.getStartedAt())).completedAt(instant(p.getCompletedAt())).build();
    }

    private TraceAnalysisItem item(TraceAnalysisItemPO p) {
        return TraceAnalysisItem.builder().id(p.getId()).jobId(p.getJobId()).sourceRunId(p.getSourceRunId())
                .analyzerType(p.getAnalyzerType()).status(p.getStatus()).outcomeStatus(p.getOutcomeStatus()).attempt(value(p.getAttempt()))
                .candidateId(p.getCandidateId()).latencyMs(p.getLatencyMs()).estimatedCost(value(p.getEstimatedCost()))
                .errorClass(p.getErrorClass()).errorMessage(p.getErrorMessage()).build();
    }

    private TraceAnalysisJobPO jobPo(TraceAnalysisJob v) {
        TraceAnalysisJobPO p = new TraceAnalysisJobPO();
        p.setId(v.getId()); p.setScope(v.getScope()); p.setAnalyzerType(v.getAnalyzerType()); p.setAnalyzerVersion(v.getAnalyzerVersion()); p.setAnalyzerConfigHash(v.getAnalyzerConfigHash());
        p.setSampleDefinitionJson(v.getSampleDefinitionJson()); p.setTraceSnapshotAt(date(v.getTraceSnapshotAt())); p.setIdempotencyKey(v.getIdempotencyKey());
        p.setStatus(v.getStatus()); p.setTotalItems(v.getTotalItems()); p.setSucceededItems(v.getSucceededItems()); p.setFailedItems(v.getFailedItems());
        p.setReservedCost(v.getReservedCost()); p.setActualCost(v.getActualCost()); p.setCreatedBy(v.getCreatedBy());
        p.setCreatedAt(date(v.getCreatedAt())); p.setStartedAt(date(v.getStartedAt())); p.setCompletedAt(date(v.getCompletedAt())); return p;
    }

    private TraceAnalysisItemPO itemPo(TraceAnalysisItem v) {
        TraceAnalysisItemPO p = new TraceAnalysisItemPO();
        p.setId(v.getId()); p.setJobId(v.getJobId()); p.setSourceRunId(v.getSourceRunId()); p.setAnalyzerType(v.getAnalyzerType());
        p.setStatus(v.getStatus()); p.setOutcomeStatus(v.getOutcomeStatus()); p.setAttempt(v.getAttempt()); p.setCandidateId(v.getCandidateId()); p.setLatencyMs(v.getLatencyMs());
        p.setEstimatedCost(v.getEstimatedCost()); p.setErrorClass(v.getErrorClass()); p.setErrorMessage(v.getErrorMessage()); return p;
    }

    private int value(Integer value) { return value == null ? 0 : value; }
    private double value(Double value) { return value == null ? 0D : value; }
    private java.time.Instant instant(Date value) { return value == null ? null : value.toInstant(); }
    private Date date(java.time.Instant value) { return value == null ? null : Date.from(value); }
}
