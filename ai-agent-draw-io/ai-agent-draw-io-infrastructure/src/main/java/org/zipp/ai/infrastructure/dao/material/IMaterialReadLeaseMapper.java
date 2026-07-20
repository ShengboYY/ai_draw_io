package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.EvidenceReadLeasePO;

import java.time.Instant;

@Mapper
public interface IMaterialReadLeaseMapper {
    String lockAuthorizedMaterial(@Param("ownerType") String ownerType,
                                  @Param("ownerKey") String ownerKey,
                                  @Param("materialId") String materialId,
                                  @Param("versionId") String versionId,
                                  @Param("revisionId") String revisionId,
                                  @Param("scopeType") String scopeType,
                                  @Param("scopeKey") String scopeKey,
                                  @Param("partialReadyAccepted") boolean partialReadyAccepted,
                                  @Param("now") Instant now);
    EvidenceReadLeasePO selectRunSourceForUpdate(@Param("ownerKey") String ownerKey,
                                                 @Param("runId") String runId,
                                                 @Param("versionId") String versionId,
                                                 @Param("revisionId") String revisionId);
    int insertLease(EvidenceReadLeasePO lease);
    EvidenceReadLeasePO selectOwnedLease(@Param("ownerType") String ownerType,
                                         @Param("ownerKey") String ownerKey,
                                         @Param("leaseId") String leaseId,
                                         @Param("runId") String runId);
    int updateLease(@Param("leaseId") String leaseId,
                    @Param("ownerKey") String ownerKey,
                    @Param("expectedStatus") String expectedStatus,
                    @Param("status") String status,
                    @Param("expiresAt") Instant expiresAt);
    int expireDue(@Param("now") Instant now, @Param("limit") int limit);
}
