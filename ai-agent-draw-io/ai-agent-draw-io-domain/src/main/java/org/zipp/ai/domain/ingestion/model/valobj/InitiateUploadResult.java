package org.zipp.ai.domain.ingestion.model.valobj;

public record InitiateUploadResult(String uploadId,
                                   UploadSessionState state,
                                   BrowserPostPolicy postPolicy) {
}
