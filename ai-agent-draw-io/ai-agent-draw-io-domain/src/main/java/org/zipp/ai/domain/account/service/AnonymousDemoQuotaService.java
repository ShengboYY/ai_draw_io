package org.zipp.ai.domain.account.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.account.model.valobj.DemoQuotaSnapshot;

import java.time.Instant;
import java.util.Locale;

@Service
public class AnonymousDemoQuotaService {

    public static final int DEFAULT_LIMIT = 5;
    private static final Instant NEVER_EXPIRES = Instant.parse("9999-12-31T23:59:59Z");

    private final int limit;
    private final UsageCounterStore store;

    public AnonymousDemoQuotaService() {
        this(DEFAULT_LIMIT, new InMemoryUsageCounterStore());
    }

    @Autowired
    public AnonymousDemoQuotaService(UsageCounterStore store) {
        this(DEFAULT_LIMIT, store);
    }

    public AnonymousDemoQuotaService(int limit) {
        this(limit, new InMemoryUsageCounterStore());
    }

    public AnonymousDemoQuotaService(int limit, UsageCounterStore store) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.store = store == null ? new InMemoryUsageCounterStore() : store;
    }

    public boolean applies(String ownerId, String customApiKey) {
        return isAnonymousOwner(ownerId) && isBlank(customApiKey);
    }

    public DemoQuotaSnapshot consumeIfNeeded(String ownerId, String customApiKey) {
        if (!applies(ownerId, customApiKey)) {
            return snapshot(ownerId);
        }
        return consume(ownerId);
    }

    public DemoQuotaSnapshot consume(String ownerId) {
        UsageCounterConsumeResult result = store.consume(demoQuotaKey(ownerId), limit, NEVER_EXPIRES, Instant.now());
        if (!result.isConsumed()) {
            throw new AnonymousDemoQuotaExceededException(DemoQuotaSnapshot.of(limit, result.getCount()));
        }
        return DemoQuotaSnapshot.of(limit, result.getCount());
    }

    public DemoQuotaSnapshot snapshot(String ownerId) {
        if (!isAnonymousOwner(ownerId)) {
            return DemoQuotaSnapshot.of(limit, 0);
        }
        return DemoQuotaSnapshot.of(limit, store.count(demoQuotaKey(ownerId), Instant.now()));
    }

    private boolean isAnonymousOwner(String ownerId) {
        return normalize(ownerId).startsWith("anon_");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String demoQuotaKey(String ownerId) {
        return "quota:anonymous-demo:" + normalize(ownerId);
    }
}
