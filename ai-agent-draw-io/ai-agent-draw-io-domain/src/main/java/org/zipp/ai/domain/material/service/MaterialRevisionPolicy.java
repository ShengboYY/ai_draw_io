package org.zipp.ai.domain.material.service;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingJob;
import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingRevision;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobStage;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingJobTarget;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;
import org.zipp.ai.domain.ingestion.service.ProcessingRevisionFingerprintPolicy;
import org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.CatalogIdFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

/** Plans immutable reprocessing revisions without changing the source MaterialVersion. */
public final class MaterialRevisionPolicy {
    public MaterialReprocessPlan plan(CatalogOwner owner, MaterialReprocessSnapshot source,
                                      Set<Integer> requestedExcludedPages, String idempotencyKey,
                                      ProcessingRevisionProfile targetProfile,
                                      CatalogIdFactory ids, Instant now) {
        if (source.pageCount() == null || source.pageCount() < 1) {
            throw new CatalogOperationException(CatalogErrorCode.REPROCESS_NOT_READY);
        }
        TreeSet<Integer> excluded = new TreeSet<>(requestedExcludedPages);
        if (excluded.stream().anyMatch(page -> page == null || page < 1 || page > source.pageCount())
                || excluded.size() >= source.pageCount()) {
            throw new IllegalArgumentException("excluded pages must leave at least one valid page");
        }
        String requestFingerprint = sha256("mutation:" + requiredKey(idempotencyKey));
        String processingFingerprint = ProcessingRevisionFingerprintPolicy.processingFingerprint(
                targetProfile.fingerprint(), excluded);
        ProcessingRevision revision = ProcessingRevision.start(ids.nextProcessingRevisionId(),
                source.versionId(), source.latestRevisionNo() + 1, processingFingerprint, excluded);
        ProcessingJob job = ProcessingJob.enqueue(ids.nextProcessingJobId(),
                ProcessingJobTarget.forRevision(revision.id()), ProcessingJobStage.EXTRACT_NATIVE, "root",
                ProcessingStageFingerprintPolicy.extractionInput(source.contentSha256(),
                        targetProfile.fingerprint()), 0, now);
        return new MaterialReprocessPlan(owner, source.materialId(), source.latestRevisionId(),
                requestFingerprint, revision, targetProfile, job, now);
    }

    private String requiredKey(String value) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key is required and must be at most 128 characters");
        }
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
