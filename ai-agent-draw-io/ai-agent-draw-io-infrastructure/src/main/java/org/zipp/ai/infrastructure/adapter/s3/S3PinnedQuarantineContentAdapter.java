package org.zipp.ai.infrastructure.adapter.s3;

import org.zipp.ai.domain.ingestion.model.valobj.DownloadedQuarantineObject;
import org.zipp.ai.domain.ingestion.port.PinnedQuarantineContentPort;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.security.MessageDigest;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Objects;

public final class S3PinnedQuarantineContentAdapter implements PinnedQuarantineContentPort {

    private final S3Client s3Client;

    public S3PinnedQuarantineContentAdapter(S3Client s3Client) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
    }

    @Override
    public boolean deletePinnedVersion(String bucket, String objectKey, String versionId) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey)
                .versionId(versionId).build());
        return true;
    }

    @Override
    public DownloadedQuarantineObject downloadPinnedVersion(String bucket, String objectKey, String versionId,
                                                             long maximumBytes, Path destination) {
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        var head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey)
                .versionId(versionId).build());
        if (head.contentLength() < 1 || head.contentLength() > maximumBytes) {
            throw new IllegalArgumentException("pinned object exceeds its bounded intake size");
        }
        Path target = Objects.requireNonNull(destination, "destination");
        s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey)
                .versionId(versionId).build(), target);
        try {
            long actualSize = Files.size(target);
            if (actualSize != head.contentLength()) {
                throw new IllegalStateException("pinned object changed length while being read");
            }
            return new DownloadedQuarantineObject(objectKey + "?versionId=" + versionId,
                    target, actualSize, sha256(target));
        } catch (IOException e) {
            throw new IllegalStateException("downloaded quarantine object could not be verified", e);
        }
    }

    private static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
