package org.zipp.ai.domain.account.service;

import org.springframework.stereotype.Service;
import org.zipp.ai.domain.account.model.valobj.DemoQuotaSnapshot;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AnonymousDemoQuotaService {

    public static final int DEFAULT_LIMIT = 5;

    private final int limit;
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public AnonymousDemoQuotaService() {
        this(DEFAULT_LIMIT);
    }

    public AnonymousDemoQuotaService(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
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
        String key = normalize(ownerId);
        AtomicInteger counter = counters.computeIfAbsent(key, ignored -> new AtomicInteger(0));
        while (true) {
            int used = counter.get();
            if (used >= limit) {
                throw new AnonymousDemoQuotaExceededException(DemoQuotaSnapshot.of(limit, used));
            }
            if (counter.compareAndSet(used, used + 1)) {
                return DemoQuotaSnapshot.of(limit, used + 1);
            }
        }
    }

    public DemoQuotaSnapshot snapshot(String ownerId) {
        if (!isAnonymousOwner(ownerId)) {
            return DemoQuotaSnapshot.of(limit, 0);
        }
        AtomicInteger counter = counters.get(normalize(ownerId));
        return DemoQuotaSnapshot.of(limit, counter == null ? 0 : counter.get());
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
}
