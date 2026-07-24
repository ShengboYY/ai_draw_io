package org.zipp.ai.domain.ingestion.port;

import java.io.InputStream;

public interface QuarantineObjectWriterPort {

    void write(String bucket, String objectKey, InputStream content, long expectedSize);
}
