package org.zipp.ai.domain.ingestion.model.valobj;

public record ProcessingJobTarget(String uploadSessionId, String revisionId) {

    public ProcessingJobTarget {
        uploadSessionId = trimToNull(uploadSessionId);
        revisionId = trimToNull(revisionId);
        if ((uploadSessionId == null) == (revisionId == null)) {
            throw new IllegalArgumentException("a processing job must target exactly one aggregate");
        }
    }

    public static ProcessingJobTarget forUpload(String uploadSessionId) {
        return new ProcessingJobTarget(uploadSessionId, null);
    }

    public static ProcessingJobTarget forRevision(String revisionId) {
        return new ProcessingJobTarget(null, revisionId);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
