package org.zipp.ai.infrastructure.adapter.s3;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.port.RevisionArtifactPort;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

public final class S3RevisionArtifactAdapter implements RevisionArtifactPort {

    private final S3Client s3Client;
    private final String materialsBucket;

    public S3RevisionArtifactAdapter(S3Client s3Client, String materialsBucket) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        if (materialsBucket == null || materialsBucket.isBlank()) {
            throw new IllegalArgumentException("materialsBucket is required");
        }
        this.materialsBucket = materialsBucket.trim();
    }

    @Override
    public StoredArtifact putImmutable(String objectKey, byte[] content, String contentType) {
        String key = requireText(objectKey, "objectKey");
        byte[] bytes = Objects.requireNonNull(content, "content").clone();
        if (bytes.length < 1) {
            throw new IllegalArgumentException("artifact content cannot be empty");
        }
        String type = requireText(contentType, "contentType");
        String hex = sha256(bytes);
        StoredArtifact existing = findCurrent(key, hex, type);
        if (existing != null) {
            return existing;
        }
        String base64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
        var response = s3Client.putObject(PutObjectRequest.builder().bucket(materialsBucket).key(key)
                        .contentType(type).contentLength((long) bytes.length).checksumSHA256(base64)
                        .metadata(Map.of("content-sha256", hex)).serverSideEncryption(ServerSideEncryption.AES256)
                        .build(), RequestBody.fromBytes(bytes));
        String versionId = requireText(response.versionId(), "artifact objectVersionId");
        HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder().bucket(materialsBucket)
                .key(key).versionId(versionId).checksumMode(ChecksumMode.ENABLED).build());
        if (head.contentLength() != bytes.length || !hex.equals(head.metadata().get("content-sha256"))
                || !base64.equals(head.checksumSHA256())) {
            deleteVersion(key, versionId);
            throw new IllegalStateException("stored revision artifact failed identity verification");
        }
        return new StoredArtifact(key, versionId, hex, bytes.length, type);
    }

    @Override
    public byte[] read(StoredArtifact artifact, long maximumBytes) {
        StoredArtifact expected = bounded(artifact, maximumBytes);
        ResponseBytes<software.amazon.awssdk.services.s3.model.GetObjectResponse> response =
                s3Client.getObjectAsBytes(GetObjectRequest.builder().bucket(materialsBucket)
                        .key(expected.objectKey()).versionId(expected.objectVersionId()).build());
        byte[] bytes = response.asByteArray();
        if (bytes.length != expected.byteSize() || !expected.contentSha256().equals(sha256(bytes))) {
            throw new IllegalStateException("revision artifact bytes did not match the fixed identity");
        }
        return bytes;
    }

    @Override
    public Path download(StoredArtifact artifact, long maximumBytes, Path destination) {
        StoredArtifact expected = bounded(artifact, maximumBytes);
        Path target = Objects.requireNonNull(destination, "destination");
        s3Client.getObject(GetObjectRequest.builder().bucket(materialsBucket)
                .key(expected.objectKey()).versionId(expected.objectVersionId()).build(), target);
        try {
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length != expected.byteSize() || !expected.contentSha256().equals(sha256(bytes))) {
                throw new IllegalStateException("downloaded artifact did not match the fixed identity");
            }
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("downloaded artifact could not be verified", e);
        }
    }

    private StoredArtifact findCurrent(String key, String expectedSha256, String contentType) {
        try {
            HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(materialsBucket).key(key).checksumMode(ChecksumMode.ENABLED).build());
            if (!expectedSha256.equals(head.metadata().get("content-sha256"))) {
                throw new IllegalStateException("immutable artifact key already contains different content");
            }
            return new StoredArtifact(key, requireText(head.versionId(), "artifact objectVersionId"),
                    expectedSha256, head.contentLength(), contentType);
        } catch (NoSuchKeyException e) {
            return null;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return null;
            }
            throw e;
        }
    }

    private static StoredArtifact bounded(StoredArtifact artifact, long maximumBytes) {
        StoredArtifact expected = Objects.requireNonNull(artifact, "artifact");
        if (maximumBytes < 1 || expected.byteSize() > maximumBytes) {
            throw new IllegalArgumentException("artifact exceeds its bounded read size");
        }
        return expected;
    }

    private void deleteVersion(String key, String versionId) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(materialsBucket)
                    .key(key).versionId(versionId).build());
        } catch (RuntimeException ignored) {
            // Orphan reconciliation can retry without changing the authoritative artifact manifest.
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
