package org.zipp.ai.domain.account.service;

import java.time.Instant;

public interface UsageCounterStore {

    UsageCounterConsumeResult consume(String key, int limit, Instant expiresAt, Instant now);

    int count(String key, Instant now);

    UsageFailureState recordFailure(String key, int maxFailures, Instant lockUntil, Instant now);

    UsageFailureState failureState(String key, Instant now);

    void clear(String key);
}
