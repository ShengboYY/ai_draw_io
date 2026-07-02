package org.zipp.ai.domain.account.service;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UsageCounterRateLimiter {

    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FailureCounter> failures = new ConcurrentHashMap<>();

    public UsageCounterRateLimiter() {
        this(Clock.systemUTC());
    }

    /** Test seam: inject a fixed clock so limit windows can be exercised deterministically. */
    public UsageCounterRateLimiter(Clock clock) {
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void consume(String bucket, String subject, String denialMessage, UsageLimitRule... rules) {
        if (rules == null || rules.length == 0) {
            return;
        }
        String scopedKey = scopedKey(bucket, subject);
        Deque<Instant> events = windows.computeIfAbsent(scopedKey, ignored -> new ArrayDeque<>());
        Instant now = clock.instant();
        synchronized (events) {
            prune(events, now.minus(maxWindow(rules)));
            for (UsageLimitRule rule : rules) {
                if (countAfter(events, now.minus(rule.getWindow())) >= rule.getMaxEvents()) {
                    throw new RateLimitExceededException(denialMessage);
                }
            }
            events.addLast(now);
        }
    }

    public boolean isLocked(String bucket, String subject) {
        String scopedKey = scopedKey(bucket, subject);
        FailureCounter counter = failures.get(scopedKey);
        if (counter == null) {
            return false;
        }
        synchronized (counter) {
            if (counter.lockedUntil == null) {
                return false;
            }
            if (clock.instant().isBefore(counter.lockedUntil)) {
                return true;
            }
            failures.remove(scopedKey, counter);
            return false;
        }
    }

    public void recordFailure(String bucket, String subject, int maxFailures, Duration lockDuration) {
        if (maxFailures <= 0 || lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("failure lock settings must be positive");
        }
        String scopedKey = scopedKey(bucket, subject);
        FailureCounter counter = failures.computeIfAbsent(scopedKey, ignored -> new FailureCounter());
        Instant now = clock.instant();
        synchronized (counter) {
            if (counter.lockedUntil != null && now.isBefore(counter.lockedUntil)) {
                return;
            }
            if (counter.lockedUntil != null) {
                counter.count = 0;
                counter.lockedUntil = null;
            }
            counter.count++;
            if (counter.count >= maxFailures) {
                counter.lockedUntil = now.plus(lockDuration);
            }
        }
    }

    public void clearFailures(String bucket, String subject) {
        failures.remove(scopedKey(bucket, subject));
    }

    private void prune(Deque<Instant> events, Instant cutoff) {
        while (!events.isEmpty() && !events.peekFirst().isAfter(cutoff)) {
            events.removeFirst();
        }
    }

    private int countAfter(Deque<Instant> events, Instant cutoff) {
        int count = 0;
        for (Instant event : events) {
            if (event.isAfter(cutoff)) {
                count++;
            }
        }
        return count;
    }

    private Duration maxWindow(UsageLimitRule[] rules) {
        Duration max = Duration.ZERO;
        for (UsageLimitRule rule : rules) {
            if (rule.getWindow().compareTo(max) > 0) {
                max = rule.getWindow();
            }
        }
        return max;
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

    private static final class FailureCounter {
        private int count;
        private Instant lockedUntil;
    }
}
