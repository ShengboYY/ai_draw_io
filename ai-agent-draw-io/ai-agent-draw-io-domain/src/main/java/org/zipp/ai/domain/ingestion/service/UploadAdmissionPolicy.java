package org.zipp.ai.domain.ingestion.service;

import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.exception.UploadAdmissionException;
import org.zipp.ai.domain.ingestion.model.valobj.UploadAdmissionRequest;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.UploadLimits;
import org.zipp.ai.domain.ingestion.model.valobj.UploadRateReservation;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;

import java.util.Set;
import java.util.Objects;

public final class UploadAdmissionPolicy {

    private static final Set<String> PDF_TYPES = Set.of("application/pdf");
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png");

    private final UploadLimits limits;

    public UploadAdmissionPolicy(UploadLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    public void validate(UploadAdmissionRequest request) {
        UploadAdmissionRequest candidate = java.util.Objects.requireNonNull(request, "request");
        boolean pdf = PDF_TYPES.contains(candidate.declaredMediaType());
        boolean image = IMAGE_TYPES.contains(candidate.declaredMediaType());
        if (!pdf && !image) {
            reject(UploadErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        if (candidate.ownerType() == OwnerType.ANONYMOUS) {
            validateAnonymous(candidate, pdf);
            return;
        }
        validateRegistered(candidate, pdf);
    }

    public UploadRateReservation rateReservation(OwnerType ownerType) {
        if (Objects.requireNonNull(ownerType, "ownerType") == OwnerType.ANONYMOUS) {
            return new UploadRateReservation(limits.anonymousWorkspaceHourly(), limits.anonymousIpHourly(),
                    Long.MAX_VALUE, limits.anonymousActiveFiles());
        }
        // Registered users are governed by storage/concurrency limits in WP2; retain a bounded DB value.
        return new UploadRateReservation(Integer.MAX_VALUE, Integer.MAX_VALUE,
                limits.registeredAccountBytes(), Integer.MAX_VALUE);
    }

    public int processingLimit(OwnerType ownerType) {
        return Objects.requireNonNull(ownerType, "ownerType") == OwnerType.ANONYMOUS
                ? limits.anonymousProcessingConcurrency() : limits.registeredProcessingConcurrency();
    }

    private void validateAnonymous(UploadAdmissionRequest request, boolean pdf) {
        if (!limits.anonymousUploadEnabled()) {
            reject(UploadErrorCode.UPLOAD_DISABLED);
        }
        if (request.target().scopeType() != MaterialScopeType.CONVERSATION
                || request.target().retentionClass() != RetentionClass.TEMPORARY) {
            reject(UploadErrorCode.ANONYMOUS_SCOPE_FORBIDDEN);
        }
        long byteLimit = pdf ? limits.anonymousPdfBytes() : limits.anonymousImageBytes();
        if (request.declaredBytes() > byteLimit) {
            reject(UploadErrorCode.DECLARED_SIZE_LIMIT);
        }
        if (request.batchFileCount() > 1) {
            reject(UploadErrorCode.BATCH_FILE_LIMIT);
        }
        if (request.activeFileCount() >= limits.anonymousActiveFiles()) {
            reject(UploadErrorCode.WORKSPACE_FILE_LIMIT);
        }
        if (request.processingCount() >= limits.anonymousProcessingConcurrency()) {
            reject(UploadErrorCode.PROCESSING_CONCURRENCY_LIMIT);
        }
        if (request.workspaceHourlyCount() >= limits.anonymousWorkspaceHourly()) {
            reject(UploadErrorCode.WORKSPACE_RATE_LIMIT);
        }
        if (request.ipHourlyCount() >= limits.anonymousIpHourly()) {
            reject(UploadErrorCode.IP_RATE_LIMIT);
        }
    }

    private void validateRegistered(UploadAdmissionRequest request, boolean pdf) {
        long byteLimit = pdf ? limits.registeredPdfBytes() : limits.registeredImageBytes();
        if (request.declaredBytes() > byteLimit) {
            reject(UploadErrorCode.DECLARED_SIZE_LIMIT);
        }
        if (request.batchFileCount() > limits.registeredBatchFiles()) {
            reject(UploadErrorCode.BATCH_FILE_LIMIT);
        }
        if (request.processingCount() >= limits.registeredProcessingConcurrency()) {
            reject(UploadErrorCode.PROCESSING_CONCURRENCY_LIMIT);
        }
        if (request.accountOriginalBytes() + request.declaredBytes() > limits.registeredAccountBytes()) {
            reject(UploadErrorCode.ACCOUNT_STORAGE_LIMIT);
        }
    }

    private void reject(UploadErrorCode code) {
        throw new UploadAdmissionException(code);
    }
}
