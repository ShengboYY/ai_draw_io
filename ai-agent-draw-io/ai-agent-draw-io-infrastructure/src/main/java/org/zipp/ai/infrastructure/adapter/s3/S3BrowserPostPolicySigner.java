package org.zipp.ai.infrastructure.adapter.s3;

import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.BrowserPostPolicy;
import org.zipp.ai.domain.ingestion.port.UploadPolicySignerPort;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class S3BrowserPostPolicySigner implements UploadPolicySignerPort {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private final AwsCredentialsProvider credentialsProvider;
    private final Region region;
    private final Clock clock;

    public S3BrowserPostPolicySigner(AwsCredentialsProvider credentialsProvider, Region region, Clock clock) {
        this.credentialsProvider = Objects.requireNonNull(credentialsProvider, "credentialsProvider");
        this.region = Objects.requireNonNull(region, "region");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public BrowserPostPolicy sign(UploadSession session) {
        UploadSession upload = Objects.requireNonNull(session, "session");
        AwsCredentials credentials = credentialsProvider.resolveCredentials();
        String shortDate = DATE.format(clock.instant());
        String amzDate = DATE_TIME.format(clock.instant());
        String scope = shortDate + "/" + region.id() + "/s3/aws4_request";
        String credential = credentials.accessKeyId() + "/" + scope;
        String securityToken = credentials instanceof AwsSessionCredentials temporary
                ? temporary.sessionToken() : null;
        String policyJson = policyJson(upload, credential, amzDate, securityToken);
        String encodedPolicy = Base64.getEncoder().encodeToString(
                policyJson.getBytes(StandardCharsets.UTF_8));
        String signature = HexFormat.of().formatHex(hmac(signingKey(credentials.secretAccessKey(), shortDate),
                encodedPolicy.getBytes(StandardCharsets.UTF_8)));

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", upload.quarantineKey());
        fields.put("Content-Type", upload.declaredMediaType());
        fields.put("x-amz-server-side-encryption", "AES256");
        fields.put("x-amz-meta-upload-id", upload.id());
        fields.put("x-amz-algorithm", ALGORITHM);
        fields.put("x-amz-credential", credential);
        fields.put("x-amz-date", amzDate);
        if (securityToken != null) {
            fields.put("x-amz-security-token", securityToken);
        }
        fields.put("policy", encodedPolicy);
        fields.put("x-amz-signature", signature);
        return new BrowserPostPolicy(endpoint(upload.quarantineBucket()), fields, upload.policyExpiresAt());
    }

    private String policyJson(UploadSession upload, String credential, String amzDate, String securityToken) {
        StringBuilder json = new StringBuilder("{\"expiration\":\"")
                .append(upload.policyExpiresAt()).append("\",\"conditions\":[")
                .append(exact("bucket", upload.quarantineBucket())).append(',')
                .append(exact("key", upload.quarantineKey())).append(',')
                .append(exact("Content-Type", upload.declaredMediaType())).append(',')
                .append(exact("x-amz-server-side-encryption", "AES256")).append(',')
                .append(exact("x-amz-meta-upload-id", upload.id())).append(',')
                .append("[\"content-length-range\",1,").append(upload.expectedSize()).append("],")
                .append(exact("x-amz-credential", credential)).append(',')
                .append(exact("x-amz-algorithm", ALGORITHM)).append(',')
                .append(exact("x-amz-date", amzDate));
        if (securityToken != null) {
            json.append(',').append(exact("x-amz-security-token", securityToken));
        }
        return json.append("]}").toString();
    }

    private String exact(String field, String value) {
        return "{\"" + jsonEscape(field) + "\":\"" + jsonEscape(value) + "\"}";
    }

    private String endpoint(String bucket) {
        return "https://" + bucket + ".s3." + region.id() + ".amazonaws.com";
    }

    private byte[] signingKey(String secret, String shortDate) {
        byte[] dateKey = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8),
                shortDate.getBytes(StandardCharsets.UTF_8));
        byte[] regionKey = hmac(dateKey, region.id().getBytes(StandardCharsets.UTF_8));
        byte[] serviceKey = hmac(regionKey, "s3".getBytes(StandardCharsets.UTF_8));
        return hmac(serviceKey, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by the JVM", e);
        }
    }

    private String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
