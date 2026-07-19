package org.zipp.ai.infrastructure.s3;

import org.junit.jupiter.api.Test;
import org.zipp.ai.infrastructure.adapter.s3.S3RevisionArtifactAdapter;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class S3RevisionArtifactAdapterTest {

    @Test
    void storesAndReadsAnExactVersionedArtifactWithAContentHash() {
        byte[] content = "canonical page".getBytes(StandardCharsets.UTF_8);
        String hex = sha256(content);
        String base64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
        AtomicInteger heads = new AtomicInteger();
        AtomicReference<PutObjectRequest> put = new AtomicReference<>();
        AtomicReference<GetObjectRequest> get = new AtomicReference<>();
        S3Client s3 = (S3Client) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{S3Client.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "headObject" -> {
                        if (heads.getAndIncrement() == 0) {
                            throw NoSuchKeyException.builder().message("missing").build();
                        }
                        yield HeadObjectResponse.builder().versionId("artifact-version").contentLength((long) content.length)
                                .checksumSHA256(base64).metadata(Map.of("content-sha256", hex)).build();
                    }
                    case "putObject" -> {
                        put.set((PutObjectRequest) args[0]);
                        yield PutObjectResponse.builder().versionId("artifact-version").checksumSHA256(base64).build();
                    }
                    case "getObjectAsBytes" -> {
                        get.set((GetObjectRequest) args[0]);
                        yield ResponseBytes.fromByteArray(GetObjectResponse.builder().contentLength((long) content.length)
                                .build(), content);
                    }
                    case "serviceName" -> "s3";
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var adapter = new S3RevisionArtifactAdapter(s3, "materials");

        var stored = adapter.putImmutable("revisions/rev_1/pages/1/canonical-page.json.gz",
                content, "application/gzip");
        byte[] loaded = adapter.read(stored, 1024);

        assertEquals(hex, stored.contentSha256());
        assertEquals("artifact-version", stored.objectVersionId());
        assertEquals(hex, put.get().metadata().get("content-sha256"));
        assertEquals("artifact-version", get.get().versionId());
        assertArrayEquals(content, loaded);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
