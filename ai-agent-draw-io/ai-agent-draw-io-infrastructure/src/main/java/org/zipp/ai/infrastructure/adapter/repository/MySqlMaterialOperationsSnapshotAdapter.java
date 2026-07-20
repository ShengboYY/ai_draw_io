package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.operations.MaterialOperationalSnapshot;
import org.zipp.ai.domain.operations.MaterialOperationsSnapshotPort;
import org.zipp.ai.infrastructure.dao.material.IMaterialOperationsMapper;
import org.zipp.ai.infrastructure.dao.material.po.MaterialOperationalSnapshotPO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Objects;

/** Read-only MySQL projection for material capability and alert evaluation. */
@Repository
public class MySqlMaterialOperationsSnapshotAdapter implements MaterialOperationsSnapshotPort {
    private static final Duration STUCK_DELETION_AGE = Duration.ofMinutes(10);
    private static final Duration CACHE_AGE = Duration.ofSeconds(25);
    private static final Duration RECONCILIATION_STALE_AGE = Duration.ofMinutes(15);
    private final IMaterialOperationsMapper mapper;
    private final Clock clock;
    private volatile MaterialOperationalSnapshot cached;

    public MySqlMaterialOperationsSnapshotAdapter(IMaterialOperationsMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    MySqlMaterialOperationsSnapshotAdapter(IMaterialOperationsMapper mapper, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized MaterialOperationalSnapshot current() {
        Instant now = clock.instant();
        if (cached != null && cached.capturedAt().plus(CACHE_AGE).isAfter(now)) return cached;
        Instant monthStart = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
                .withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        MaterialOperationalSnapshotPO row = Objects.requireNonNull(mapper.selectSnapshot(
                now, now.minus(Duration.ofHours(24)), monthStart, now.minus(STUCK_DELETION_AGE),
                now.minus(RECONCILIATION_STALE_AGE)),
                "material operations snapshot");
        cached = new MaterialOperationalSnapshot(row.getCapturedAt(), row.getQueuedJobs(), row.getRunningJobs(),
                row.getFailedJobsLast24Hours(), row.getOldestQueuedSeconds(), row.getActiveReadLeases(),
                row.getExpiredReadLeases(), row.getStuckDeletingMaterials(), row.getOldestDeletionSeconds(),
                row.getIndexedPagesThisMonth(), row.getStaleVectorBatches(), row.getOpenProjectionRepairs(),
                row.getPendingOrphanDeletions(), row.getPurgingGenerations());
        return cached;
    }
}
