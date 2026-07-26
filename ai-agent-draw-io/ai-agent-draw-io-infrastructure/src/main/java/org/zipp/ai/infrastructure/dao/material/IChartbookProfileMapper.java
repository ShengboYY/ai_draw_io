package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.ChartbookProfileAuditPO;
import org.zipp.ai.infrastructure.dao.material.po.ChartbookProfilePO;

import java.time.Instant;

/** MyBatis boundary for the owner-fenced current Profile and its immutable history. */
@Mapper
public interface IChartbookProfileMapper {
    ChartbookProfilePO selectCurrent(@Param("ownerKey") String ownerKey,
                                     @Param("chartbookId") String chartbookId);

    ChartbookProfilePO selectVersion(@Param("ownerKey") String ownerKey,
                                     @Param("chartbookId") String chartbookId,
                                     @Param("version") long version);

    ChartbookProfileAuditPO selectAudit(@Param("ownerKey") String ownerKey,
                                         @Param("chartbookId") String chartbookId,
                                         @Param("idempotencyKey") String idempotencyKey);

    int insertEmptyProfile(@Param("ownerKey") String ownerKey,
                           @Param("chartbookId") String chartbookId,
                           @Param("now") Instant now);

    int updateCurrent(@Param("ownerKey") String ownerKey,
                      @Param("chartbookId") String chartbookId,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("version") long version,
                      @Param("instructions") String instructions,
                      @Param("goal") String goal,
                      @Param("summary") String summary,
                      @Param("glossaryJson") String glossaryJson,
                      @Param("defaultStyleJson") String defaultStyleJson,
                      @Param("stableConstraintsJson") String stableConstraintsJson,
                      @Param("profileState") String profileState,
                      @Param("updatedAt") Instant updatedAt);

    int insertVersion(@Param("ownerKey") String ownerKey,
                      @Param("chartbookId") String chartbookId,
                      @Param("version") long version,
                      @Param("instructions") String instructions,
                      @Param("goal") String goal,
                      @Param("summary") String summary,
                      @Param("glossaryJson") String glossaryJson,
                      @Param("defaultStyleJson") String defaultStyleJson,
                      @Param("stableConstraintsJson") String stableConstraintsJson,
                      @Param("profileState") String profileState,
                      @Param("updatedAt") Instant updatedAt);

    int insertAudit(@Param("auditId") String auditId,
                    @Param("ownerKey") String ownerKey,
                    @Param("chartbookId") String chartbookId,
                    @Param("idempotencyKey") String idempotencyKey,
                    @Param("version") long version,
                    @Param("contentDigest") String contentDigest,
                    @Param("createdAt") Instant createdAt);
}
