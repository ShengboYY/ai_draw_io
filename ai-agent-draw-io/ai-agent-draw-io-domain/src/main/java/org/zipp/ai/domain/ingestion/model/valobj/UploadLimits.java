package org.zipp.ai.domain.ingestion.model.valobj;

public record UploadLimits(boolean anonymousUploadEnabled,
                           long anonymousPdfBytes,
                           long anonymousImageBytes,
                           long registeredPdfBytes,
                           long registeredImageBytes,
                           long registeredAccountBytes,
                           int anonymousActiveFiles,
                           int anonymousProcessingConcurrency,
                           int registeredProcessingConcurrency,
                           int anonymousWorkspaceHourly,
                           int anonymousIpHourly,
                           int registeredBatchFiles) {

    private static final long MEBIBYTE = 1024L * 1024L;
    private static final long GIBIBYTE = 1024L * MEBIBYTE;

    public UploadLimits {
        if (anonymousPdfBytes < 1 || anonymousImageBytes < 1 || registeredPdfBytes < 1
                || registeredImageBytes < 1 || registeredAccountBytes < 1
                || anonymousActiveFiles < 1 || anonymousProcessingConcurrency < 1
                || registeredProcessingConcurrency < 1 || anonymousWorkspaceHourly < 1
                || anonymousIpHourly < 1 || registeredBatchFiles < 1) {
            throw new IllegalArgumentException("upload limits must be positive");
        }
    }

    public static UploadLimits defaults(boolean anonymousUploadEnabled) {
        return new UploadLimits(anonymousUploadEnabled,
                20L * MEBIBYTE, 10L * MEBIBYTE,
                50L * MEBIBYTE, 15L * MEBIBYTE,
                2L * GIBIBYTE,
                3, 1, 2, 10, 30, 10);
    }
}
