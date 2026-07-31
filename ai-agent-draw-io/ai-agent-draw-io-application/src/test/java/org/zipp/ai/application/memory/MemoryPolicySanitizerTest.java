package org.zipp.ai.application.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MemoryPolicySanitizerTest {
    @Test
    void safeTextIsNormalized() {
        MemoryPolicySanitizer.TextSanitizationOutcome.Accepted accepted = assertInstanceOf(
                MemoryPolicySanitizer.TextSanitizationOutcome.Accepted.class,
                new MemoryPolicySanitizer().sanitizeText("  Prefer   short labels  "));

        assertEquals("Prefer short labels", accepted.text());
    }

    @Test
    void sensitiveTextIsRejectedWithoutEchoingContent() {
        String secret = "api_key=do-not-leak";
        MemoryPolicySanitizer.TextSanitizationOutcome outcome =
                new MemoryPolicySanitizer().sanitizeText(secret);

        MemoryPolicySanitizer.TextSanitizationOutcome.Rejected rejected = assertInstanceOf(
                MemoryPolicySanitizer.TextSanitizationOutcome.Rejected.class, outcome);
        assertEquals("MEMORY_SECRET_FORBIDDEN", rejected.code());
        assertFalse(outcome.toString().contains(secret));
    }

    @Test
    void profileOwnedFieldsStayOutsideMemory() {
        MemoryPolicySanitizer sanitizer = new MemoryPolicySanitizer();

        assertEquals(true, sanitizer.isProfileOwnedField("default-style"));
        assertInstanceOf(
                MemoryPolicySanitizer.TextSanitizationOutcome.Rejected.class,
                sanitizer.sanitizeText("Use default style"));
    }
}
