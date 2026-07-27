package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.QuarantineObjectVersion;

import java.util.Optional;

public interface QuarantineObjectPort {
    Optional<QuarantineObjectVersion> headLatestVersion(String bucket, String objectKey);
}
