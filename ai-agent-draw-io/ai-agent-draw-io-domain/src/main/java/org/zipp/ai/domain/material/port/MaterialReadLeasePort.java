package org.zipp.ai.domain.material.port;

import org.zipp.ai.domain.material.model.aggregate.EvidenceReadLease;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialReadLeaseRequest;
import org.zipp.ai.domain.material.model.valobj.MaterialReadLeaseStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Atomic authorization and persistence boundary sharing the material row lock. */
public interface MaterialReadLeasePort {
    Optional<EvidenceReadLease> acquire(MaterialReadLeaseRequest request, EvidenceReadLease candidate);
    Optional<List<EvidenceReadLease>> acquireAll(List<MaterialReadLeaseRequest> requests,
                                                 List<EvidenceReadLease> candidates);
    Optional<EvidenceReadLease> findOwned(CatalogOwner owner, String leaseId, String runId);
    boolean save(EvidenceReadLease changed, MaterialReadLeaseStatus expectedStatus);
    int expireDue(Instant expiryTime, int limit);
}
