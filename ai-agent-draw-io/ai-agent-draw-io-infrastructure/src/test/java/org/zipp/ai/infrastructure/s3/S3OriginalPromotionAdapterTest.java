package org.zipp.ai.infrastructure.s3;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.OriginalPromotionWork;
import org.zipp.ai.infrastructure.adapter.s3.S3OriginalPromotionAdapter;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectResult;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.lang.reflect.Proxy;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3OriginalPromotionAdapterTest {

    @Test
    void promoteCopiesAndVerifiesTheExactPinnedQuarantineVersion() {
        AtomicReference<CopyObjectRequest> captured = new AtomicReference<>();
        AtomicReference<HeadObjectRequest> capturedHead = new AtomicReference<>();
        String sha256 = "a".repeat(64);
        String checksum = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256));
        S3Client s3 = (S3Client) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{S3Client.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "copyObject" -> {
                        captured.set((CopyObjectRequest) args[0]);
                        yield CopyObjectResponse.builder().versionId("destination-version")
                                .copyObjectResult(CopyObjectResult.builder().eTag("etag").build()).build();
                    }
                    case "headObject" -> {
                        capturedHead.set((HeadObjectRequest) args[0]);
                        yield HeadObjectResponse.builder().contentLength(42L)
                                .versionId("destination-version").checksumSHA256(checksum)
                                .metadata(Map.of("content-sha256", sha256)).build();
                    }
                    case "serviceName" -> "s3";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        OriginalPromotionWork work = new OriginalPromotionWork(
                "upl_1", 3, "quarantine", "incoming/owner/file name.pdf", "v+1/exact",
                "original/owner/blob", "blob_1", "mat_1", "ver_1", "rev_1",
                "application/pdf", 42, sha256, "d".repeat(64), null);

        var promoted = new S3OriginalPromotionAdapter(s3, "materials").promote(work);

        assertEquals("quarantine/incoming/owner/file%20name.pdf?versionId=v%2B1%2Fexact",
                captured.get().copySource());
        assertEquals("materials", captured.get().destinationBucket());
        assertEquals(ChecksumAlgorithm.SHA256, captured.get().checksumAlgorithm());
        assertEquals(ChecksumMode.ENABLED, capturedHead.get().checksumMode());
        assertEquals("original/owner/blob", promoted.objectKey());
        assertEquals("destination-version", promoted.objectVersionId());
        assertEquals(checksum, promoted.checksumSha256());
    }

    @Test
    void checksumFailureDeletesTheUncommittedDestinationVersion() {
        AtomicReference<DeleteObjectRequest> deleted = new AtomicReference<>();
        S3Client s3 = (S3Client) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{S3Client.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "copyObject" -> CopyObjectResponse.builder().versionId("orphan-version").build();
                    case "headObject" -> HeadObjectResponse.builder().contentLength(42L)
                            .metadata(Map.of("content-sha256", "a".repeat(64)))
                            .checksumSHA256("wrong-checksum").build();
                    case "deleteObject" -> {
                        deleted.set((DeleteObjectRequest) args[0]);
                        yield null;
                    }
                    case "serviceName" -> "s3";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        OriginalPromotionWork work = new OriginalPromotionWork(
                "upl_1", 3, "quarantine", "incoming/opaque", "source-version",
                "original/owner/blob", "blob_1", "mat_1", "ver_1", "rev_1",
                "application/pdf", 42, "a".repeat(64), "d".repeat(64), null);

        assertThrows(IllegalStateException.class,
                () -> new S3OriginalPromotionAdapter(s3, "materials").promote(work));

        assertEquals("orphan-version", deleted.get().versionId());
        assertEquals("original/owner/blob", deleted.get().key());
    }
}
