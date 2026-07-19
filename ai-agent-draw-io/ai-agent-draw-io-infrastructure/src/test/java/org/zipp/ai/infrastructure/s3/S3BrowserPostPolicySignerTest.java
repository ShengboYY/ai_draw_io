package org.zipp.ai.infrastructure.s3;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;
import org.zipp.ai.infrastructure.adapter.s3.S3BrowserPostPolicySigner;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3BrowserPostPolicySignerTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void policyBindsOpaqueKeySizeMediaTypeEncryptionAndTemporaryCredential() {
        UploadSession session = UploadSession.create(
                "upl_1", OwnerType.USER, "usr_1", "idem_1", "guide.pdf",
                "application/pdf", 128L, "a".repeat(64),
                new UploadTarget(MaterialScopeType.CONVERSATION, "conv_1", RetentionClass.TEMPORARY),
                null, "quarantine", "incoming/token/upl_1/obj_1", NOW.plusSeconds(600), NOW);
        S3BrowserPostPolicySigner signer = new S3BrowserPostPolicySigner(
                StaticCredentialsProvider.create(AwsSessionCredentials.create(
                        "AKIDEXAMPLE", "secret-example", "session-token")),
                Region.AP_SOUTHEAST_2, Clock.fixed(NOW, ZoneOffset.UTC));

        var policy = signer.sign(session);
        String decoded = new String(Base64.getDecoder().decode(policy.fields().get("policy")),
                StandardCharsets.UTF_8);

        assertEquals("https://quarantine.s3.ap-southeast-2.amazonaws.com", policy.url());
        assertEquals("incoming/token/upl_1/obj_1", policy.fields().get("key"));
        assertEquals("application/pdf", policy.fields().get("Content-Type"));
        assertEquals("AES256", policy.fields().get("x-amz-server-side-encryption"));
        assertEquals("session-token", policy.fields().get("x-amz-security-token"));
        assertTrue(policy.fields().get("x-amz-signature").matches("[0-9a-f]{64}"));
        assertTrue(decoded.contains("\"bucket\":\"quarantine\""));
        assertTrue(decoded.contains("[\"content-length-range\",1,128]"));
        assertTrue(decoded.contains("\"x-amz-meta-upload-id\":\"upl_1\""));
        assertTrue(decoded.contains("\"x-amz-security-token\":\"session-token\""));
        assertFalse(policy.fields().toString().contains("secret-example"));
        assertFalse(decoded.contains("guide.pdf"));
    }
}
