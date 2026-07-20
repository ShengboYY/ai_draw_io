package org.zipp.ai.domain.material.service;

import org.zipp.ai.domain.material.model.aggregate.EvidenceReadLease;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.CatalogIdFactory;
import org.zipp.ai.domain.material.port.MaterialReadLeasePort;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

public final class MaterialReadLeaseService {
    private final MaterialReadLeasePort leases;
    private final CatalogIdFactory ids;
    private final Clock clock;

    public MaterialReadLeaseService(MaterialReadLeasePort leases, CatalogIdFactory ids, Clock clock) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EvidenceReadLease acquire(MaterialReadLeaseRequest request) {
        return acquireAll(List.of(request)).get(0);
    }

    /** Acquires every source authorization atomically so a run never observes a partial source set. */
    public List<EvidenceReadLease> acquireAll(List<MaterialReadLeaseRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("at least one read lease request is required");
        }
        var candidates = requests.stream().map(request -> EvidenceReadLease.issue(ids.nextReadLeaseId(),
                request.owner().ownerKey(), request.materialId(), request.versionId(),
                request.revisionId(), request.runId(), clock.instant())).toList();
        return leases.acquireAll(List.copyOf(requests), candidates)
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.READ_LEASE_DENIED));
    }

    public EvidenceReadLease renew(CatalogOwner owner, String leaseId, String runId) {
        EvidenceReadLease lease = owned(owner, leaseId, runId);
        lease.renew(clock.instant());
        if (!leases.save(lease, MaterialReadLeaseStatus.ACTIVE)) {
            throw new CatalogOperationException(CatalogErrorCode.READ_LEASE_DENIED);
        }
        return lease;
    }

    public void release(CatalogOwner owner, String leaseId, String runId) {
        EvidenceReadLease lease = owned(owner, leaseId, runId);
        lease.release();
        if (!leases.save(lease, MaterialReadLeaseStatus.ACTIVE)) {
            throw new CatalogOperationException(CatalogErrorCode.READ_LEASE_DENIED);
        }
    }

    public int expireDue(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return leases.expireDue(clock.instant(), limit);
    }

    private EvidenceReadLease owned(CatalogOwner owner, String leaseId, String runId) {
        return leases.findOwned(owner, required(leaseId, "leaseId"), required(runId, "runId"))
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.READ_LEASE_DENIED));
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
