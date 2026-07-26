package org.zipp.ai.domain.chartbook.service;

import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfile;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfilePatch;
import org.zipp.ai.domain.chartbook.port.ChartbookProfilePort;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.time.Clock;
import java.util.Objects;

/** Application boundary for owner-fenced Profile reads and optimistic updates. */
public final class ChartbookProfileService {
    private final ChartbookProfilePort profiles;
    private final Clock clock;

    public ChartbookProfileService(ChartbookProfilePort profiles, Clock clock) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ChartbookProfile find(CatalogOwner owner, String chartbookId) {
        owner.requireRegisteredUser();
        return profiles.find(owner, required(chartbookId, "chartbookId"));
    }

    public ChartbookProfile update(CatalogOwner owner, String chartbookId, ChartbookProfilePatch patch,
                                   long expectedVersion, String idempotencyKey) {
        owner.requireRegisteredUser();
        if (expectedVersion < 0) throw new IllegalArgumentException("profile version must not be negative");
        String key = required(idempotencyKey, "Idempotency-Key");
        if (key.length() > 128) throw new IllegalArgumentException("Idempotency-Key is too long");
        return profiles.update(owner, required(chartbookId, "chartbookId"),
                Objects.requireNonNull(patch, "patch"), expectedVersion, key, clock.instant());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
