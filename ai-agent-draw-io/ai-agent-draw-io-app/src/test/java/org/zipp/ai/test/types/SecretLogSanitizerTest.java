package org.zipp.ai.test.types;

import org.junit.Test;
import org.zipp.ai.types.util.SecretLogSanitizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SecretLogSanitizerTest {

    @Test
    public void shouldRedactSecretFieldsBearerTokensAndQueryKeys() {
        String rawLog = "{\"apiKey\":\"sk-live-secret\",\"customApiKey\":\"user-secret\",\"sseEndpoint\":\"sse?api_key=bce-secret\"} "
                + "Authorization=[Bearer sk-header-secret] customApiKey=user-secret";

        String sanitized = SecretLogSanitizer.sanitize(rawLog);

        assertFalse(sanitized.contains("sk-live-secret"));
        assertFalse(sanitized.contains("user-secret"));
        assertFalse(sanitized.contains("bce-secret"));
        assertFalse(sanitized.contains("sk-header-secret"));
        assertEquals("{\"apiKey\":\"***\",\"customApiKey\":\"***\",\"sseEndpoint\":\"sse?api_key=***\"} "
                + "Authorization=[Bearer ***] customApiKey=***", sanitized);
    }

    @Test
    public void shouldRenderAuthorizationWithoutSecretValue() {
        assertEquals("(none)", SecretLogSanitizer.maskAuthorization(null));
        assertEquals("(none)", SecretLogSanitizer.maskAuthorization(""));
        assertEquals("Bearer ***", SecretLogSanitizer.maskAuthorization("sk-live-secret"));
    }
}
