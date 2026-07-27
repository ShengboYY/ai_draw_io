package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.MaterialPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialScopeLinkPO;

import java.time.Instant;

@Mapper
public interface IMaterialFoundationMapper {
    int insertMaterial(MaterialPO material);
    MaterialPO selectByIdForOwner(@Param("id") String id,
                                  @Param("ownerType") String ownerType,
                                  @Param("ownerKey") String ownerKey);
    int insertScopeLink(MaterialScopeLinkPO scopeLink);
    int extendTemporaryExpiry(@Param("id") String id,
                              @Param("ownerType") String ownerType,
                              @Param("ownerKey") String ownerKey,
                              @Param("expectedGeneration") long expectedGeneration,
                              @Param("activityAt") Instant activityAt,
                              @Param("expiresAt") Instant expiresAt);
}
