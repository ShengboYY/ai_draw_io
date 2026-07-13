package org.zipp.ai.infrastructure.adapter.repository.evaluation.controlplane;

import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane.*;
import org.zipp.ai.domain.agent.service.evaluation.controlplane.IEvalRunStore;
import org.zipp.ai.infrastructure.dao.IEvalRunMapper;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.*;

import java.util.*;

@Repository
public class EvalRunRepository implements IEvalRunStore {
    private final IEvalRunMapper mapper;
    public EvalRunRepository(IEvalRunMapper mapper) { this.mapper = mapper; }
    @Override public void insertRun(EvalRun value) { mapper.insertRun(runPo(value)); }
    @Override public void updateRun(EvalRun value) { mapper.updateRun(runPo(value)); }
    @Override public Optional<EvalRun> findRun(String id) { return Optional.ofNullable(mapper.selectRun(id)).map(this::run); }
    @Override public Optional<EvalRun> findByIdempotencyKey(String key) { return Optional.ofNullable(mapper.selectRunByIdempotencyKey(key)).map(this::run); }
    @Override public List<EvalRun> listRuns(int limit, int offset) { return mapper.selectRuns(limit, offset).stream().map(this::run).toList(); }
    @Override public void saveEpisode(EvalEpisode value) { mapper.upsertEpisode(episodePo(value)); }
    @Override public Optional<EvalEpisode> findEpisode(String id) { return Optional.ofNullable(mapper.selectEpisode(id)).map(this::episode); }
    @Override public List<EvalEpisode> listEpisodes(String runId) { return mapper.selectEpisodes(runId).stream().map(this::episode).toList(); }
    @Override @Transactional public void replaceGraders(String episodeId, List<EvalGraderResultRecord> values) { mapper.deleteGraders(episodeId); for (EvalGraderResultRecord value : values) mapper.insertGrader(graderPo(value)); }
    @Override public List<EvalGraderResultRecord> listGraders(String episodeId) { return mapper.selectGraders(episodeId).stream().map(this::grader).toList(); }

    private EvalRun run(EvalRunPO po) { return EvalRun.builder().id(po.getId()).mode(EvalRunMode.valueOf(po.getMode())).datasetId(po.getDatasetId()).datasetVersion(po.getDatasetVersion()).baselineRef(po.getBaselineRef()).candidateRef(po.getCandidateRef()).executionProfileHash(po.getExecutionProfileHash()).idempotencyKey(po.getIdempotencyKey()).repetitions(po.getRepetitions()).gitSha(po.getGitSha()).graderManifestJson(po.getGraderManifestJson()).reportRef(po.getReportRef()).status(EvalRunStatus.valueOf(po.getStatus())).createdBy(po.getCreatedBy()).createdAt(instant(po.getCreatedAt())).startedAt(instant(po.getStartedAt())).completedAt(instant(po.getCompletedAt())).build(); }
    private EvalRunPO runPo(EvalRun value) { EvalRunPO po = new EvalRunPO(); po.setId(value.getId()); po.setMode(value.getMode().name()); po.setDatasetId(value.getDatasetId()); po.setDatasetVersion(value.getDatasetVersion()); po.setBaselineRef(value.getBaselineRef()); po.setCandidateRef(value.getCandidateRef()); po.setExecutionProfileHash(value.getExecutionProfileHash()); po.setIdempotencyKey(value.getIdempotencyKey()); po.setRepetitions(value.getRepetitions()); po.setGitSha(value.getGitSha()); po.setGraderManifestJson(value.getGraderManifestJson()); po.setReportRef(value.getReportRef()); po.setStatus(value.getStatus().name()); po.setCreatedBy(value.getCreatedBy()); po.setCreatedAt(date(value.getCreatedAt())); po.setStartedAt(date(value.getStartedAt())); po.setCompletedAt(date(value.getCompletedAt())); return po; }
    private EvalEpisode episode(EvalEpisodePO po) { List<String> refs = po.getArtifactRefsJson() == null ? List.of() : JSON.parseArray(po.getArtifactRefsJson(), String.class); return EvalEpisode.builder().id(po.getId()).evalRunId(po.getEvalRunId()).caseId(po.getCaseId()).caseVersion(po.getCaseVersion()).repetition(po.getRepetition()).attempt(po.getAttempt()).status(EvalEpisodeStatus.valueOf(po.getStatus())).traceRef(po.getTraceRef()).artifactRefs(refs).latencyMs(po.getLatencyMs()).inputTokens(po.getInputTokens()).outputTokens(po.getOutputTokens()).estimatedCost(po.getEstimatedCost()).errorClass(po.getErrorClass()).errorMessage(po.getErrorMessage()).build(); }
    private EvalEpisodePO episodePo(EvalEpisode value) { EvalEpisodePO po = new EvalEpisodePO(); po.setId(value.getId()); po.setEvalRunId(value.getEvalRunId()); po.setCaseId(value.getCaseId()); po.setCaseVersion(value.getCaseVersion()); po.setRepetition(value.getRepetition()); po.setAttempt(value.getAttempt()); po.setStatus(value.getStatus().name()); po.setTraceRef(value.getTraceRef()); po.setArtifactRefsJson(JSON.toJSONString(value.getArtifactRefs())); po.setLatencyMs(value.getLatencyMs()); po.setInputTokens(value.getInputTokens()); po.setOutputTokens(value.getOutputTokens()); po.setEstimatedCost(value.getEstimatedCost()); po.setErrorClass(value.getErrorClass()); po.setErrorMessage(value.getErrorMessage()); return po; }
    private EvalGraderResultRecord grader(EvalGraderResultPO po) { return EvalGraderResultRecord.builder().episodeId(po.getEpisodeId()).graderName(po.getGraderName()).graderVersion(po.getGraderVersion()).status(EvalEpisodeStatus.valueOf(po.getStatus())).severity(po.getSeverity()).score(po.getScore()).evidenceJson(po.getEvidenceJson()).build(); }
    private EvalGraderResultPO graderPo(EvalGraderResultRecord value) { EvalGraderResultPO po = new EvalGraderResultPO(); po.setEpisodeId(value.getEpisodeId()); po.setGraderName(value.getGraderName()); po.setGraderVersion(value.getGraderVersion()); po.setStatus(value.getStatus().name()); po.setSeverity(value.getSeverity()); po.setScore(value.getScore()); po.setEvidenceJson(value.getEvidenceJson()); return po; }
    private java.time.Instant instant(Date value) { return value == null ? null : value.toInstant(); }
    private Date date(java.time.Instant value) { return value == null ? null : Date.from(value); }
}
