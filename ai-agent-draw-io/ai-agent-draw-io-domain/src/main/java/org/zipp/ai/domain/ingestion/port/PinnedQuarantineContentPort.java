package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.DownloadedQuarantineObject;

import java.nio.file.Path;

public interface PinnedQuarantineContentPort {
    DownloadedQuarantineObject downloadPinnedVersion(String bucket, String objectKey, String versionId,
                                                      long maximumBytes, Path destination);
    default boolean deletePinnedVersion(String bucket, String objectKey, String versionId) {
        return false;
    }
}
