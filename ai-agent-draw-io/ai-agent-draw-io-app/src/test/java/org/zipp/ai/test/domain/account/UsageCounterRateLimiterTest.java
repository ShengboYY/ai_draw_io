package org.zipp.ai.test.domain.account;

import org.junit.Before;
import org.junit.Test;
import org.zipp.ai.domain.account.service.RateLimitExceededException;
import org.zipp.ai.domain.account.service.UsageCounterRateLimiter;
import org.zipp.ai.domain.account.service.UsageLimitRule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UsageCounterRateLimiterTest {

    private MutableClock clock;
    private UsageCounterRateLimiter limiter;

    @Before
    public void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-02T10:00:00Z"));
        limiter = new UsageCounterRateLimiter(clock);
    }

    @Test
    public void consumeDeniesUntilSlidingWindowExpires() {
        UsageLimitRule oncePerMinute = UsageLimitRule.of(1, Duration.ofSeconds(60));

        limiter.consume("email", "alice@example.com", "Too many emails.", oncePerMinute);
        assertRateLimited(() ->
                limiter.consume("email", "alice@example.com", "Too many emails.", oncePerMinute));

        clock.advance(Duration.ofSeconds(60));
        limiter.consume("email", "alice@example.com", "Too many emails.", oncePerMinute);
    }

    @Test
    public void consumeKeepsBucketsAndSubjectsSeparate() {
        UsageLimitRule oncePerMinute = UsageLimitRule.of(1, Duration.ofSeconds(60));

        limiter.consume("verification", "alice@example.com", "Too many emails.", oncePerMinute);
        limiter.consume("password-reset", "alice@example.com", "Too many emails.", oncePerMinute);
        limiter.consume("verification", "bob@example.com", "Too many emails.", oncePerMinute);

        assertRateLimited(() ->
                limiter.consume("verification", "alice@example.com", "Too many emails.", oncePerMinute));
    }

    @Test
    public void consecutiveFailuresLockAndThenResetAfterDuration() {
        for (int i = 0; i < 5; i++) {
            assertFalse(limiter.isLocked("login-failures", "alice@example.com"));
            limiter.recordFailure("login-failures", "alice@example.com", 5, Duration.ofMinutes(15));
        }

        assertTrue(limiter.isLocked("login-failures", "alice@example.com"));
        clock.advance(Duration.ofMinutes(15));
        assertFalse(limiter.isLocked("login-failures", "alice@example.com"));
    }

    @Test
    public void clearingConsecutiveFailuresPreventsLockout() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("login-failures", "alice@example.com", 5, Duration.ofMinutes(15));
        }

        limiter.clearFailures("login-failures", "alice@example.com");
        limiter.recordFailure("login-failures", "alice@example.com", 5, Duration.ofMinutes(15));

        assertFalse(limiter.isLocked("login-failures", "alice@example.com"));
    }

    private void assertRateLimited(Runnable action) {
        try {
            action.run();
        } catch (RateLimitExceededException expected) {
            return;
        }
        throw new AssertionError("expected rate limit denial");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
