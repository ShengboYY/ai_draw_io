package org.zipp.ai.domain.ingestion.exception;

import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;

public final class UploadAdmissionException extends IllegalArgumentException {

    private final UploadErrorCode code;

    public UploadAdmissionException(UploadErrorCode code) {
        super(code.name());
        this.code = code;
    }

    public UploadErrorCode code() {
        return code;
    }
}
