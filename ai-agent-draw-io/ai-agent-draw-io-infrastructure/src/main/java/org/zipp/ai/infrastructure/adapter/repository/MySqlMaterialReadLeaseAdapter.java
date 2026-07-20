package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.material.model.aggregate.EvidenceReadLease;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialReadLeasePort;
import org.zipp.ai.infrastructure.dao.material.IMaterialReadLeaseMapper;
import org.zipp.ai.infrastructure.dao.material.po.EvidenceReadLeasePO;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Lease adapter that reauthorizes and inserts under the same material row lock. */
@Repository
public class MySqlMaterialReadLeaseAdapter implements MaterialReadLeasePort {
    private final IMaterialReadLeaseMapper mapper;

    public MySqlMaterialReadLeaseAdapter(IMaterialReadLeaseMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public Optional<EvidenceReadLease> acquire(MaterialReadLeaseRequest request,
                                               EvidenceReadLease candidate) {
        return acquireAll(List.of(request), List.of(candidate)).map(items -> items.get(0));
    }

    @Override
    @Transactional
    public Optional<List<EvidenceReadLease>> acquireAll(List<MaterialReadLeaseRequest> requests,
                                                        List<EvidenceReadLease> candidates) {
        if (requests == null || candidates == null || requests.size() != candidates.size()
                || requests.isEmpty()) {
            throw new IllegalArgumentException("read lease request and candidate sets must match");
        }
        record Pair(int index, MaterialReadLeaseRequest request, EvidenceReadLease candidate) { }
        List<Pair> ordered = java.util.stream.IntStream.range(0, requests.size())
                .mapToObj(index -> new Pair(index, requests.get(index), candidates.get(index)))
                .sorted(Comparator.comparing((Pair pair) -> pair.request().materialId())
                        .thenComparing(pair -> pair.request().versionId())
                        .thenComparing(pair -> pair.request().revisionId()))
                .toList();
        java.util.ArrayList<EvidenceReadLease> acquired = new java.util.ArrayList<>(
                java.util.Collections.nCopies(requests.size(), null));
        for (Pair pair : ordered) {
            Optional<EvidenceReadLease> lease = acquireOne(pair.request(), pair.candidate());
            if (lease.isEmpty()) {
                // Throwing marks the transaction rollback-only, including leases inserted earlier in this batch.
                throw new CatalogOperationException(CatalogErrorCode.READ_LEASE_DENIED);
            }
            acquired.set(pair.index(), lease.get());
        }
        return Optional.of(List.copyOf(acquired));
    }

    private Optional<EvidenceReadLease> acquireOne(MaterialReadLeaseRequest request,
                                                   EvidenceReadLease candidate) {
        String locked = mapper.lockAuthorizedMaterial(request.owner().ownerType().name(),
                request.owner().ownerKey(), request.materialId(), request.versionId(),
                request.revisionId(), request.scopeType().name(), request.scopeKey(),
                request.partialReadyAccepted(), candidate.createdAt());
        if (locked == null) return Optional.empty();

        EvidenceReadLeasePO existing = mapper.selectRunSourceForUpdate(request.owner().ownerKey(),
                request.runId(), request.versionId(), request.revisionId());
        if (existing != null) {
            EvidenceReadLease lease = toDomain(existing);
            return lease.status() == MaterialReadLeaseStatus.ACTIVE
                    && candidate.createdAt().isBefore(lease.expiresAt())
                    ? Optional.of(lease) : Optional.empty();
        }
        if (mapper.insertLease(toPo(candidate)) != 1) return Optional.empty();
        return Optional.of(candidate);
    }

    @Override
    public Optional<EvidenceReadLease> findOwned(CatalogOwner owner, String leaseId, String runId) {
        return Optional.ofNullable(mapper.selectOwnedLease(owner.ownerType().name(), owner.ownerKey(),
                leaseId, runId)).map(this::toDomain);
    }

    @Override
    public boolean save(EvidenceReadLease changed, MaterialReadLeaseStatus expectedStatus) {
        return mapper.updateLease(changed.id(), changed.ownerKey(), expectedStatus.name(),
                changed.status().name(), changed.expiresAt()) == 1;
    }

    @Override
    public int expireDue(Instant expiryTime, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        return mapper.expireDue(Objects.requireNonNull(expiryTime, "expiryTime"), limit);
    }

    private EvidenceReadLease toDomain(EvidenceReadLeasePO po) {
        return EvidenceReadLease.rehydrate(po.getId(), po.getOwnerKey(), po.getMaterialId(),
                po.getVersionId(), po.getRevisionId(), po.getRunId(), po.getCreatedAt(),
                po.getExpiresAt(), po.getMaxExpiresAt(), MaterialReadLeaseStatus.valueOf(po.getStatus()));
    }

    private EvidenceReadLeasePO toPo(EvidenceReadLease lease) {
        EvidenceReadLeasePO po = new EvidenceReadLeasePO();
        po.setId(lease.id());
        po.setOwnerKey(lease.ownerKey());
        po.setMaterialId(lease.materialId());
        po.setVersionId(lease.versionId());
        po.setRevisionId(lease.revisionId());
        po.setRunId(lease.runId());
        po.setStatus(lease.status().name());
        po.setExpiresAt(lease.expiresAt());
        po.setMaxExpiresAt(lease.maxExpiresAt());
        po.setCreatedAt(lease.createdAt());
        return po;
    }
}
