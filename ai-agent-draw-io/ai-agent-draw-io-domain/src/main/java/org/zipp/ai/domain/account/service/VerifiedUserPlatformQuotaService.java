package org.zipp.ai.domain.account.service;

import org.springframework.stereotype.Service;
import org.zipp.ai.domain.account.model.valobj.PlatformDailyQuotaSnapshot;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class VerifiedUserPlatformQuotaService {

    public static final int DEFAULT_DAILY_LIMIT = 20;

    private final int dailyLimit;
    private final Clock clock;
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public VerifiedUserPlatformQuotaService() {
        this(DEFAULT_DAILY_LIMIT, Clock.systemUTC());
    }

    public VerifiedUserPlatformQuotaService(Clock clock) {
        this(DEFAULT_DAILY_LIMIT, clock);
    }

    public VerifiedUserPlatformQuotaService(int dailyLimit, Clock clock) {
        if (dailyLimit <= 0) {
            throw new IllegalArgumentException("dailyLimit must be positive");
        }
        this.dailyLimit = dailyLimit;
        this.clock = clock == null ? Clock.systemUTC() : clock.withZone(ZoneOffset.UTC);
    }

    public boolean applies(String ownerId, String customApiKey) {
        return isVerifiedUserOwner(ownerId) && isBlank(customApiKey);
    }

    public PlatformDailyQuotaSnapshot consumeIfNeeded(String ownerId, String customApiKey) {
        if (!applies(ownerId, customApiKey)) {
            return snapshot(ownerId);
        }
        return consume(ownerId);
    }

    public PlatformDailyQuotaSnapshot consume(String ownerId) {
        LocalDate today = todayUtc();
        AtomicInteger counter = counters.computeIfAbsent(scopedKey(ownerId, today), ignored -> new AtomicInteger(0));
        while (true) {
            int used = counter.get();
            if (used >= dailyLimit) {
                throw new PlatformDailyQuotaExceededException(PlatformDailyQuotaSnapshot.of(dailyLimit, used, today));
            }
            if (counter.compareAndSet(used, used + 1)) {
                return PlatformDailyQuotaSnapshot.of(dailyLimit, used + 1, today);
            }
        }
    }

    public PlatformDailyQuotaSnapshot snapshot(String ownerId) {
        LocalDate today = todayUtc();
        if (!isVerifiedUserOwner(ownerId)) {
            return PlatformDailyQuotaSnapshot.of(dailyLimit, 0, today);
        }
        AtomicInteger counter = counters.get(scopedKey(ownerId, today));
        return PlatformDailyQuotaSnapshot.of(dailyLimit, counter == null ? 0 : counter.get(), today);
    }

    private LocalDate todayUtc() {
        return LocalDate.now(clock);
    }

    private boolean isVerifiedUserOwner(String ownerId) {
        return normalize(ownerId).startsWith("usr_");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String scopedKey(String ownerId, LocalDate quotaDate) {
        // UTC date is part of the key so a new day starts with a fresh counter.
        return normalize(ownerId) + ":" + quotaDate;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
