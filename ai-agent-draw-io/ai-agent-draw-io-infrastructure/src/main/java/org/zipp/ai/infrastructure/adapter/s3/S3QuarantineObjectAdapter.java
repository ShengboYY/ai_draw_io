package org.zipp.ai.infrastructure.adapter.s3;

import org.zipp.ai.domain.ingestion.model.valobj.QuarantineObjectVersion;
import org.zipp.ai.domain.ingestion.port.QuarantineObjectPort;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.Objects;
import java.util.Optional;

public final class S3QuarantineObjectAdapter implements QuarantineObjectPort {

    private final S3Client s3Client;

    public S3QuarantineObjectAdapter(S3Client s3Client) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
    }

    @Override
    public Optional<QuarantineObjectVersion> headLatestVersion(String bucket, String objectKey) {
        try {
            var response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(requireText(bucket, "bucket"))
                    .key(requireText(objectKey, "objectKey"))
                    .checksumMode("ENABLED")
                    .build());
            if (response.versionId() == null || response.versionId().isBlank()) {
                // Version pinning is the boundary that prevents a replay from replacing scanned bytes.
                throw new IllegalStateException("quarantine bucket must have S3 versioning enabled");
            }
            return Optional.of(new QuarantineObjectVersion(response.versionId(), response.eTag(),
                    response.checksumSHA256(), response.contentLength()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
