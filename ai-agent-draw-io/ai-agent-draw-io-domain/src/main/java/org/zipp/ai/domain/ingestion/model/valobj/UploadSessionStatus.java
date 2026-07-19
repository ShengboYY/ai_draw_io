package org.zipp.ai.domain.ingestion.model.valobj;

public record UploadSessionStatus(String uploadId,
                                  UploadSessionState state,
                                  String pinnedObjectVersionId,
                                  String materialId,
                                  String versionId,
                                  String errorCode) {
}
