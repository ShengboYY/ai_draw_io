package org.zipp.ai.ingestion.worker.security;

import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;

public final class UploadSecurityException extends RuntimeException {
    private final UploadErrorCode errorCode;

    public UploadSecurityException(UploadErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public UploadErrorCode errorCode() {
        return errorCode;
    }
}
