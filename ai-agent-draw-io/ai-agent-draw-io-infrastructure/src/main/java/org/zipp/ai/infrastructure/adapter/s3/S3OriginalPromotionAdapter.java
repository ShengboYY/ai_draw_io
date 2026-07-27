package org.zipp.ai.infrastructure.adapter.s3;

import org.zipp.ai.domain.ingestion.model.valobj.OriginalPromotionWork;
import org.zipp.ai.domain.ingestion.model.valobj.PromotedOriginal;
import org.zipp.ai.domain.ingestion.port.OriginalPromotionPort;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class S3OriginalPromotionAdapter implements OriginalPromotionPort {

    private final S3Client s3Client;
    private final String materialsBucket;

    public S3OriginalPromotionAdapter(S3Client s3Client, String materialsBucket) {
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client");
        if (materialsBucket == null || materialsBucket.isBlank()) {
            throw new IllegalArgumentException("materialsBucket is required");
        }
        this.materialsBucket = materialsBucket.trim();
    }

    @Override
    public PromotedOriginal promote(OriginalPromotionWork work) {
        OriginalPromotionWork source = Objects.requireNonNull(work, "work");
        var response = s3Client.copyObject(CopyObjectRequest.builder()
                .copySource(copySource(source.quarantineBucket(), source.quarantineKey(),
                        source.quarantineVersionId()))
                .destinationBucket(materialsBucket)
                .destinationKey(source.destinationKey())
                .contentType(source.detectedMediaType())
                .metadataDirective(MetadataDirective.REPLACE)
                .metadata(Map.of(
                        "content-sha256", source.contentSha256(),
                        "content-blob-id", source.contentBlobId()))
                .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .build());
        if (response.versionId() == null || response.versionId().isBlank()) {
            throw new IllegalStateException("versioned materials bucket did not return a destination version");
        }
        try {
            var head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(materialsBucket).key(source.destinationKey()).versionId(response.versionId())
                    .checksumMode(ChecksumMode.ENABLED).build());
            String expectedChecksum = Base64.getEncoder().encodeToString(
                    HexFormat.of().parseHex(source.contentSha256()));
            if (head.contentLength() != source.byteSize()
                    || !source.contentSha256().equals(head.metadata().get("content-sha256"))
                    || !expectedChecksum.equals(head.checksumSHA256())) {
                throw new IllegalStateException("promoted original failed size or identity verification");
            }
            String eTag = response.copyObjectResult() == null ? head.eTag() : response.copyObjectResult().eTag();
            return new PromotedOriginal(source.destinationKey(), response.versionId(), eTag,
                    head.checksumSHA256(), head.contentLength());
        } catch (RuntimeException e) {
            deleteVersion(source.destinationKey(), response.versionId());
            throw e;
        }
    }

    @Override
    public void discard(PromotedOriginal promotedOriginal) {
        PromotedOriginal promoted = Objects.requireNonNull(promotedOriginal, "promotedOriginal");
        deleteVersion(promoted.objectKey(), promoted.objectVersionId());
    }

    static String copySource(String bucket, String key, String versionId) {
        String encodedPath = Arrays.stream((bucket + "/" + key).split("/", -1))
                .map(S3OriginalPromotionAdapter::encode)
                .collect(Collectors.joining("/"));
        return encodedPath + "?versionId=" + encode(versionId);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private void deleteVersion(String objectKey, String objectVersionId) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(materialsBucket)
                    .key(objectKey).versionId(objectVersionId).build());
        } catch (RuntimeException ignored) {
            // A later orphan reconciliation can retry cleanup without changing the authoritative DB pin.
        }
    }
}
