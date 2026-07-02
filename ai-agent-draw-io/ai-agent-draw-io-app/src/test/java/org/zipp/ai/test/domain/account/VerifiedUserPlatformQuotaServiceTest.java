package org.zipp.ai.test.domain.account;

import org.junit.Test;
import org.zipp.ai.domain.account.model.valobj.PlatformDailyQuotaSnapshot;
import org.zipp.ai.domain.account.service.PlatformDailyQuotaExceededException;
import org.zipp.ai.domain.account.service.VerifiedUserPlatformQuotaService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VerifiedUserPlatformQuotaServiceTest {

    @Test
    public void shouldAllowTwentyPlatformRequestsPerUtcDay() {
        VerifiedUserPlatformQuotaService quotaService = new VerifiedUserPlatformQuotaService(
                Clock.fixed(Instant.parse("2026-07-02T23:30:00Z"), ZoneOffset.UTC));

        PlatformDailyQuotaSnapshot snapshot = null;
        for (int i = 0; i < 20; i++) {
            snapshot = quotaService.consume("usr_alice");
        }

        assertEquals(20, snapshot.getLimit());
        assertEquals(20, snapshot.getUsed());
        assertEquals(0, snapshot.getRemaining());
        assertTrue(snapshot.isExhausted());
        assertEquals("2026-07-02", snapshot.getQuotaDate());
        assertQuotaExceeded(() -> quotaService.consume("usr_alice"));
    }

    @Test
    public void shouldResetUsageOnNextUtcDay() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-02T23:59:00Z"));
        VerifiedUserPlatformQuotaService quotaService = new VerifiedUserPlatformQuotaService(clock);

        quotaService.consume("usr_alice");
        clock.setInstant(Instant.parse("2026-07-03T00:00:00Z"));
        PlatformDailyQuotaSnapshot snapshot = quotaService.consume("usr_alice");

        assertEquals(1, snapshot.getUsed());
        assertEquals(19, snapshot.getRemaining());
        assertFalse(snapshot.isExhausted());
        assertEquals("2026-07-03", snapshot.getQuotaDate());
    }

    private void assertQuotaExceeded(Runnable action) {
        try {
            action.run();
        } catch (PlatformDailyQuotaExceededException expected) {
            assertEquals(20, expected.getQuota().getLimit());
            assertEquals(20, expected.getQuota().getUsed());
            return;
        }
        throw new AssertionError("expected daily platform quota denial");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
