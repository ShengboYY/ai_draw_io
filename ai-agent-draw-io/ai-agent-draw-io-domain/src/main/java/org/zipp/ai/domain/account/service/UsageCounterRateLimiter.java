package org.zipp.ai.domain.account.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Service
public class UsageCounterRateLimiter {

    private final Clock clock;
    private final UsageCounterStore store;

    public UsageCounterRateLimiter() {
        this(Clock.systemUTC(), new InMemoryUsageCounterStore());
    }

    @Autowired
    public UsageCounterRateLimiter(UsageCounterStore store) {
        this(Clock.systemUTC(), store);
    }

    /** Test seam: inject a fixed clock so limit windows can be exercised deterministically. */
    public UsageCounterRateLimiter(Clock clock) {
        this(clock, new InMemoryUsageCounterStore());
    }

    public UsageCounterRateLimiter(Clock clock, UsageCounterStore store) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.store = store == null ? new InMemoryUsageCounterStore() : store;
    }

    public void consume(String bucket, String subject, String denialMessage, UsageLimitRule... rules) {
        if (rules == null || rules.length == 0) {
            return;
        }
        String scopedKey = scopedKey(bucket, subject);
        Instant now = clock.instant();
        for (UsageLimitRule rule : rules) {
            if (store.count(rateKey(scopedKey, rule), now) >= rule.getMaxEvents()) {
                throw new RateLimitExceededException(denialMessage);
            }
        }
        for (UsageLimitRule rule : rules) {
            UsageCounterConsumeResult result = store.consume(
                    rateKey(scopedKey, rule), rule.getMaxEvents(), now.plus(rule.getWindow()), now);
            if (!result.isConsumed()) {
                throw new RateLimitExceededException(denialMessage);
            }
        }
    }

    public boolean isLocked(String bucket, String subject) {
        Instant now = clock.instant();
        return store.failureState(failureKey(scopedKey(bucket, subject)), now).isLocked(now);
    }

    public void recordFailure(String bucket, String subject, int maxFailures, Duration lockDuration) {
        if (maxFailures <= 0 || lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("failure lock settings must be positive");
        }
        Instant now = clock.instant();
        store.recordFailure(failureKey(scopedKey(bucket, subject)), maxFailures, now.plus(lockDuration), now);
    }

    public void clearFailures(String bucket, String subject) {
        store.clear(failureKey(scopedKey(bucket, subject)));
    }

    private String rateKey(String scopedKey, UsageLimitRule rule) {
        return "rate:" + scopedKey + ":" + rule.getWindow().toMillis();
    }

    private String failureKey(String scopedKey) {
        return "failure:" + scopedKey;
    }

    private String scopedKey(String bucket, String subject) {
        return normalize(bucket) + ":" + normalize(subject);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

}
