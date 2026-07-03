package org.zipp.ai.domain.account.service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryUsageCounterStore implements UsageCounterStore {

    private static final Instant NEVER_EXPIRES = Instant.parse("9999-12-31T23:59:59Z");

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public UsageCounterConsumeResult consume(String key, int limit, Instant expiresAt, Instant now) {
        if (limit <= 0 || expiresAt == null || now == null) {
            throw new IllegalArgumentException("counter settings must be positive");
        }
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry(now));
        synchronized (entry) {
            boolean expired = entry.isExpired(now);
            int current = expired ? 0 : entry.count;
            if (current >= limit) {
                return UsageCounterConsumeResult.rejected(current);
            }
            entry.count = current + 1;
            entry.lockedUntil = null;
            // Keep the first expiry for active windows so attempts do not extend the window.
            entry.expiresAt = expired ? expiresAt : entry.expiresAt;
            return UsageCounterConsumeResult.consumed(entry.count);
        }
    }

    @Override
    public int count(String key, Instant now) {
        Entry entry = entries.get(key);
        if (entry == null || now == null) {
            return 0;
        }
        synchronized (entry) {
            if (entry.isExpired(now)) {
                entries.remove(key, entry);
                return 0;
            }
            return entry.count;
        }
    }

    @Override
    public UsageFailureState recordFailure(String key, int maxFailures, Instant lockUntil, Instant now) {
        if (maxFailures <= 0 || lockUntil == null || now == null) {
            throw new IllegalArgumentException("failure settings must be positive");
        }
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry(now));
        synchronized (entry) {
            if (entry.lockedUntil != null && now.isBefore(entry.lockedUntil)) {
                return UsageFailureState.of(entry.count, entry.lockedUntil);
            }
            int current = entry.isExpired(now) ? 0 : entry.count;
            entry.count = current + 1;
            entry.lockedUntil = entry.count >= maxFailures ? lockUntil : null;
            entry.expiresAt = entry.lockedUntil == null ? NEVER_EXPIRES : lockUntil;
            return UsageFailureState.of(entry.count, entry.lockedUntil);
        }
    }

    @Override
    public UsageFailureState failureState(String key, Instant now) {
        Entry entry = entries.get(key);
        if (entry == null || now == null) {
            return UsageFailureState.of(0, null);
        }
        synchronized (entry) {
            if (entry.isExpired(now)) {
                entries.remove(key, entry);
                return UsageFailureState.of(0, null);
            }
            return UsageFailureState.of(entry.count, entry.lockedUntil);
        }
    }

    @Override
    public void clear(String key) {
        entries.remove(key);
    }

    private static final class Entry {
        private int count;
        private Instant expiresAt;
        private Instant lockedUntil;

        private Entry(Instant expiresAt) {
            this.expiresAt = expiresAt;
        }

        private boolean isExpired(Instant now) {
            return expiresAt == null || !now.isBefore(expiresAt);
        }
    }
}
