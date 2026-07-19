package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.retrieval.model.valobj.*;
import org.zipp.ai.domain.retrieval.port.IndexGenerationCompatibilityPort;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;
import org.zipp.ai.infrastructure.dao.material.IProcessingJobMapper;
import org.zipp.ai.infrastructure.dao.material.IVectorProjectionMapper;
import org.zipp.ai.infrastructure.dao.material.po.*;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** MySQL transaction boundary for compatibility snapshots and atomic generation switching. */
@Repository
public class MySqlIndexGenerationCompatibilityAdapter implements IndexGenerationCompatibilityPort {
    private final IVectorProjectionMapper mapper;
    private final MySqlVectorProjectionPersistence persistence;

    public MySqlIndexGenerationCompatibilityAdapter(IVectorProjectionMapper mapper,
                                                      IProcessingJobMapper jobMapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.persistence = new MySqlVectorProjectionPersistence(mapper, jobMapper);
    }

    @Override
    @Transactional
    public GenerationBackfillStatus synchronize(VectorGenerationProfile profile, int batchSize, Instant now) {
        VectorGenerationProfile targetProfile = Objects.requireNonNull(profile, "profile");
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        Instant synchronizedAt = Objects.requireNonNull(now, "now");
        persistence.persistGeneration(targetProfile);
        GenerationBackfillStatus initial = status(targetProfile.generationId());
        if (initial.state() == IndexGenerationState.ACTIVE
                && targetProfile.generationId().equals(initial.activeGenerationId())) {
            enqueuePendingGenerationPublications(targetProfile.generationId(), batchSize, synchronizedAt);
            return initial;
        }
        if (targetProfile.generationId().equals(initial.activeGenerationId())) return initial;
        if (initial.state() != IndexGenerationState.BUILDING
                && initial.state() != IndexGenerationState.SHADOW) {
            return initial;
        }
        String campaignFingerprint = VectorGenerationProfile.sha256(
                targetProfile.generationFingerprint() + ":" + targetProfile.tokenizerFingerprint()
                        + ":COMPATIBILITY_CAMPAIGN_V1");
        mapper.insertCompatibilityProfile(targetProfile.generationId(),
                targetProfile.tokenizerFingerprint(), campaignFingerprint, synchronizedAt);
        if (!targetProfile.tokenizerFingerprint().equals(
                mapper.selectCompatibilityTokenizer(targetProfile.generationId()))) {
            throw new IllegalStateException("generation compatibility profile collided");
        }
        int added = mapper.insertRequiredGenerationTargets(targetProfile.generationId(),
                targetProfile.tokenizerFingerprint(), synchronizedAt);
        if (added > 0 && mapper.advanceCompatibilityTargetGeneration(
                targetProfile.generationId(), added) != 1) {
            throw new IllegalStateException("compatibility target generation was not advanced");
        }
        for (VectorProjectionWorkPO row : mapper.selectPendingGenerationTargets(
                targetProfile.generationId(), batchSize)) {
            CompatibilityProjectionWork work = new CompatibilityProjectionWork(
                    persistence.context(row), persistence.profile(row));
            ProcessingJob job = ProcessingJob.enqueue(compatibilityJobId(work),
                    ProcessingJobTarget.forRevision(work.context().revisionId()),
                    ProcessingJobStage.BUILD_COMPATIBILITY_PROJECTION,
                    work.workKey(), work.inputFingerprint(), 0, synchronizedAt);
            persistence.enqueue(job);
        }
        return status(targetProfile.generationId());
    }

    @Override
    @Transactional
    public boolean beginShadow(GenerationBackfillStatus expected, Instant now) {
        GenerationBackfillStatus source = Objects.requireNonNull(expected, "expected");
        Instant shadowStartedAt = Objects.requireNonNull(now, "now");
        if (source.state() != IndexGenerationState.BUILDING || !source.complete()
                || source.activeGenerationId() == null) return false;
        String tokenizer = mapper.selectCompatibilityTokenizer(source.generationId());
        if (tokenizer == null || mapper.lockGenerationState(source.generationId()) == null) return false;
        int added = mapper.insertRequiredGenerationTargets(source.generationId(), tokenizer, shadowStartedAt);
        if (added > 0 && mapper.advanceCompatibilityTargetGeneration(source.generationId(), added) != 1) {
            throw new IllegalStateException("compatibility target generation was not advanced");
        }
        mapper.lockGenerationTargets(source.generationId());
        if (!source.equals(status(source.generationId()))) return false;
        return mapper.beginGenerationShadow(source.generationId(), shadowStartedAt) == 1;
    }

    @Override
    @Transactional
    public boolean recordShadowReport(GenerationShadowReport report) {
        GenerationShadowReport source = Objects.requireNonNull(report, "report");
        GenerationShadowReportPO po = toPo(source);
        mapper.insertShadowReport(po);
        return source.equals(shadowReport(mapper.selectShadowReport(
                source.generationId(), source.reportId())));
    }

    @Override
    public Optional<GenerationBackfillStatus> findStatus(String generationId) {
        GenerationBackfillStatusPO po = mapper.selectGenerationBackfillStatus(
                required(generationId, "generationId"));
        return Optional.ofNullable(po).map(this::status);
    }

    @Override
    public Optional<GenerationShadowReport> findShadowReport(String generationId, String reportId) {
        return Optional.ofNullable(mapper.selectShadowReport(required(generationId, "generationId"),
                required(reportId, "reportId"))).map(this::shadowReport);
    }

    @Override
    @Transactional
    public boolean activate(GenerationBackfillStatus expected, GenerationShadowReport report,
                            Instant activatedAt, Instant rollbackUntil) {
        GenerationBackfillStatus source = Objects.requireNonNull(expected, "expected");
        GenerationShadowReport evidence = Objects.requireNonNull(report, "report");
        Instant activationTime = Objects.requireNonNull(activatedAt, "activatedAt");
        Instant rollbackDeadline = Objects.requireNonNull(rollbackUntil, "rollbackUntil");
        if (!rollbackDeadline.isAfter(activationTime)
                || source.state() != IndexGenerationState.SHADOW || !source.complete()
                || source.activeGenerationId() == null) return false;
        String activeGenerationId = mapper.selectActiveGenerationForUpdate();
        if (!source.activeGenerationId().equals(activeGenerationId)
                || mapper.selectGenerationForUpdate(source.generationId()) == null) return false;
        String tokenizer = mapper.selectCompatibilityTokenizer(source.generationId());
        int added = mapper.insertRequiredGenerationTargets(source.generationId(), tokenizer, activationTime);
        if (added > 0 && mapper.advanceCompatibilityTargetGeneration(source.generationId(), added) != 1) {
            throw new IllegalStateException("compatibility target generation was not advanced");
        }
        mapper.lockGenerationTargets(source.generationId());
        GenerationBackfillStatus current = status(source.generationId());
        GenerationShadowReport persisted = shadowReport(mapper.selectShadowReport(
                evidence.generationId(), evidence.reportId()));
        if (!source.equals(current) || !evidence.equals(persisted)) return false;
        if (mapper.retireActiveGeneration(activeGenerationId, activationTime, rollbackDeadline) != 1
                || mapper.activateShadowGeneration(source.generationId(), activeGenerationId,
                        evidence.reportId(), activationTime) != 1) {
            throw new IllegalStateException("index generation switch lost its atomic boundary");
        }
        enqueuePendingGenerationPublications(source.generationId(), 100, activationTime);
        return true;
    }

    @Override
    @Transactional
    public boolean rollback(String activeGenerationId, Instant rolledBackAt) {
        String candidateId = required(activeGenerationId, "activeGenerationId");
        Instant rollbackTime = Objects.requireNonNull(rolledBackAt, "rolledBackAt");
        if (!candidateId.equals(mapper.selectActiveGenerationForUpdate())) return false;
        RagIndexGenerationPO candidate = mapper.selectGenerationForUpdate(candidateId);
        if (candidate == null || candidate.getPreviousGenerationId() == null) return false;
        RagIndexGenerationPO previous = mapper.selectGenerationForUpdate(candidate.getPreviousGenerationId());
        if (previous == null || !IndexGenerationState.RETIRED.name().equals(previous.getState())
                || previous.getRollbackUntil() == null
                || !rollbackTime.isBefore(previous.getRollbackUntil())) return false;
        if (mapper.retireRolledBackGeneration(candidateId, rollbackTime) != 1
                || mapper.restoreRetiredGeneration(previous.getId(), rollbackTime) != 1) {
            throw new IllegalStateException("index generation rollback lost its atomic boundary");
        }
        return true;
    }

    private GenerationBackfillStatus status(String generationId) {
        GenerationBackfillStatusPO po = mapper.selectGenerationBackfillStatus(generationId);
        if (po == null) throw new IllegalStateException("index generation does not exist");
        return status(po);
    }

    private GenerationBackfillStatus status(GenerationBackfillStatusPO po) {
        return new GenerationBackfillStatus(po.getGenerationId(),
                IndexGenerationState.valueOf(po.getGenerationState()), po.getActiveGenerationId(),
                po.getTargetGeneration(), po.getRequiredRevisionCount(), po.getReadyRevisionCount(),
                po.getExpectedVectorCount(), po.getIndexedVectorCount(), po.getReadyManifestCount());
    }

    private GenerationShadowReportPO toPo(GenerationShadowReport report) {
        GenerationShadowReportPO po = new GenerationShadowReportPO();
        po.setReportId(report.reportId());
        po.setSchemaVersion(report.schemaVersion());
        po.setIndexGenerationId(report.generationId());
        po.setBaselineGenerationId(report.baselineGenerationId());
        po.setTargetGeneration(report.targetGeneration());
        po.setPolicyFingerprint(report.policyFingerprint());
        po.setSampleCount(report.sampleCount());
        po.setAuthorizationMismatchCount(report.authorizationMismatchCount());
        po.setCandidateRecallAt40(report.candidateRecallAt40());
        po.setBaselineRecallAt40(report.baselineRecallAt40());
        po.setCandidateNdcgAt16(report.candidateNdcgAt16());
        po.setBaselineNdcgAt16(report.baselineNdcgAt16());
        po.setCandidateP95LatencyMs(report.candidateP95LatencyMs());
        po.setBaselineP95LatencyMs(report.baselineP95LatencyMs());
        po.setEvaluatedAt(report.createdAt());
        return po;
    }

    private GenerationShadowReport shadowReport(GenerationShadowReportPO po) {
        Objects.requireNonNull(po, "shadow report");
        return new GenerationShadowReport(po.getSchemaVersion(), po.getReportId(),
                po.getIndexGenerationId(), po.getBaselineGenerationId(), po.getTargetGeneration(),
                po.getPolicyFingerprint(), po.getSampleCount(), po.getAuthorizationMismatchCount(),
                po.getCandidateRecallAt40(), po.getBaselineRecallAt40(),
                po.getCandidateNdcgAt16(), po.getBaselineNdcgAt16(),
                po.getCandidateP95LatencyMs(), po.getBaselineP95LatencyMs(), po.getEvaluatedAt());
    }

    private String compatibilityJobId(CompatibilityProjectionWork work) {
        return "job_" + VectorGenerationProfile.sha256(work.context().revisionId() + ":"
                + work.profile().generationId() + ":BUILD_COMPATIBILITY_PROJECTION").substring(0, 40);
    }

    private void enqueuePendingGenerationPublications(String generationId, int limit, Instant now) {
        for (VectorProjectionWorkPO row : mapper.selectPendingGenerationPublications(generationId, limit)) {
            String workKey = RevisionPublicationWork.publicationWorkKey(generationId);
            String inputFingerprint = RevisionPublicationWork.publicationInputFingerprint(
                    row.getProjectionManifestSha256(), row.getProjectionManifestHash());
            String jobId = "job_" + VectorGenerationProfile.sha256(row.getRevisionId() + ":"
                    + generationId + ":PUBLISH_REVISION").substring(0, 40);
            persistence.enqueue(ProcessingJob.enqueue(jobId,
                    ProcessingJobTarget.forRevision(row.getRevisionId()),
                    ProcessingJobStage.PUBLISH_REVISION, workKey, inputFingerprint, 0, now));
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
