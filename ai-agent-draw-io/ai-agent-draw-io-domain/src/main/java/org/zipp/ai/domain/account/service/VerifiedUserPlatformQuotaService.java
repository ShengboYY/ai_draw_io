package org.zipp.ai.domain.account.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.account.model.valobj.PlatformDailyQuotaSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;

@Service
public class VerifiedUserPlatformQuotaService {

    public static final int DEFAULT_DAILY_LIMIT = 20;

    private final int dailyLimit;
    private final Clock clock;
    private final UsageCounterStore store;

    public VerifiedUserPlatformQuotaService() {
        this(DEFAULT_DAILY_LIMIT, Clock.systemUTC(), new InMemoryUsageCounterStore());
    }

    @Autowired
    public VerifiedUserPlatformQuotaService(UsageCounterStore store) {
        this(DEFAULT_DAILY_LIMIT, Clock.systemUTC(), store);
    }

    public VerifiedUserPlatformQuotaService(Clock clock) {
        this(DEFAULT_DAILY_LIMIT, clock, new InMemoryUsageCounterStore());
    }

    public VerifiedUserPlatformQuotaService(int dailyLimit, Clock clock) {
        this(dailyLimit, clock, new InMemoryUsageCounterStore());
    }

    public VerifiedUserPlatformQuotaService(int dailyLimit, Clock clock, UsageCounterStore store) {
        if (dailyLimit <= 0) {
            throw new IllegalArgumentException("dailyLimit must be positive");
        }
        this.dailyLimit = dailyLimit;
        this.clock = clock == null ? Clock.systemUTC() : clock.withZone(ZoneOffset.UTC);
        this.store = store == null ? new InMemoryUsageCounterStore() : store;
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
        UsageCounterConsumeResult result = store.consume(
                platformQuotaKey(ownerId, today), dailyLimit, startOfNextDay(today), clock.instant());
        if (!result.isConsumed()) {
            throw new PlatformDailyQuotaExceededException(
                    PlatformDailyQuotaSnapshot.of(dailyLimit, result.getCount(), today));
        }
        return PlatformDailyQuotaSnapshot.of(dailyLimit, result.getCount(), today);
    }

    public PlatformDailyQuotaSnapshot snapshot(String ownerId) {
        LocalDate today = todayUtc();
        if (!isVerifiedUserOwner(ownerId)) {
            return PlatformDailyQuotaSnapshot.of(dailyLimit, 0, today);
        }
        int used = store.count(platformQuotaKey(ownerId, today), clock.instant());
        return PlatformDailyQuotaSnapshot.of(dailyLimit, used, today);
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

    private String platformQuotaKey(String ownerId, LocalDate quotaDate) {
        // UTC date is part of the key so a new day starts with a fresh counter.
        return "quota:verified-platform:" + normalize(ownerId) + ":" + quotaDate;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Instant startOfNextDay(LocalDate quotaDate) {
        return quotaDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
