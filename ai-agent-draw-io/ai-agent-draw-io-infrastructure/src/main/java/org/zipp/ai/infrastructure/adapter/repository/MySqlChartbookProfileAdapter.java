package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfile;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfilePatch;
import org.zipp.ai.domain.chartbook.port.ChartbookProfilePort;
import org.zipp.ai.domain.material.model.valobj.CatalogErrorCode;
import org.zipp.ai.domain.material.model.valobj.CatalogOperationException;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.infrastructure.dao.material.IChartbookProfileMapper;
import org.zipp.ai.infrastructure.dao.material.po.ChartbookProfileAuditPO;
import org.zipp.ai.infrastructure.dao.material.po.ChartbookProfilePO;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persists Profile updates with owner fencing, immutable versions and idempotent audit. */
@Repository
public class MySqlChartbookProfileAdapter implements ChartbookProfilePort {
    private final IChartbookProfileMapper mapper;

    public MySqlChartbookProfileAdapter(IChartbookProfileMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public ChartbookProfile find(CatalogOwner owner, String chartbookId) {
        ChartbookProfilePO current = current(owner, chartbookId);
        return ChartbookProfilePersistenceCodec.decode(current);
    }

    @Override
    @Transactional
    public ChartbookProfile update(CatalogOwner owner, String chartbookId, ChartbookProfilePatch patch,
                                   long expectedVersion, String idempotencyKey, Instant now) {
        Objects.requireNonNull(patch, "patch");
        Objects.requireNonNull(now, "now");
        ChartbookProfileAuditPO audit = mapper.selectAudit(owner.ownerKey(), chartbookId, idempotencyKey);
        if (audit != null) {
            ChartbookProfilePO historic = mapper.selectVersion(owner.ownerKey(), chartbookId, audit.getVersion());
            if (historic == null) {
                throw new IllegalStateException("profile audit version is not available");
            }
            // An idempotent retry returns the exact committed version, even after a later update.
            return ChartbookProfilePersistenceCodec.decode(historic);
        }

        ChartbookProfilePO current = current(owner, chartbookId);
        if ("ARCHIVED".equals(current.getChartbookStatus())) {
            throw new CatalogOperationException(CatalogErrorCode.CHARTBOOK_ARCHIVED);
        }
        ChartbookProfile currentDomain = ChartbookProfilePersistenceCodec.decode(current);
        if (currentDomain.version() != expectedVersion) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        ChartbookProfile next = currentDomain.apply(patch, now);
        int updated = mapper.updateCurrent(owner.ownerKey(), chartbookId, expectedVersion, next.version(),
                next.instructions(), next.goal(), next.summary(),
                ChartbookProfilePersistenceCodec.glossaryJson(next),
                ChartbookProfilePersistenceCodec.defaultStyleJson(next),
                ChartbookProfilePersistenceCodec.stableConstraintsJson(next),
                next.profileState().name(), next.updatedAt());
        if (updated != 1) {
            throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
        }
        mapper.insertVersion(owner.ownerKey(), chartbookId, next.version(), next.instructions(), next.goal(),
                next.summary(), ChartbookProfilePersistenceCodec.glossaryJson(next),
                ChartbookProfilePersistenceCodec.defaultStyleJson(next),
                ChartbookProfilePersistenceCodec.stableConstraintsJson(next),
                next.profileState().name(), next.updatedAt());
        mapper.insertAudit("profile_audit_" + UUID.randomUUID(), owner.ownerKey(), chartbookId,
                idempotencyKey, next.version(), ChartbookProfilePersistenceCodec.digest(next), now);
        return next;
    }

    private ChartbookProfilePO current(CatalogOwner owner, String chartbookId) {
        ChartbookProfilePO current = mapper.selectCurrent(owner.ownerKey(), chartbookId);
        if (current == null) {
            throw new CatalogOperationException(CatalogErrorCode.CHARTBOOK_NOT_FOUND);
        }
        if (current.getProfileRowId() == null) {
            // A pre-profile row is materialized once without consulting legacy preferences_json.
            Instant createdAt = Instant.now();
            if (mapper.insertEmptyProfile(owner.ownerKey(), chartbookId, createdAt) == 1) {
                mapper.insertVersion(owner.ownerKey(), chartbookId, 0, "", "", "", "{}", "{}", "[]",
                        "EMPTY", createdAt);
            }
            current = mapper.selectCurrent(owner.ownerKey(), chartbookId);
        }
        if (current == null) {
            throw new IllegalStateException("profile row was not materialized");
        }
        return current;
    }
}
