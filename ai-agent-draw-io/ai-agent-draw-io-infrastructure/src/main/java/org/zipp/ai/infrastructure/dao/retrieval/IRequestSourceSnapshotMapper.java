package org.zipp.ai.infrastructure.dao.retrieval;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.ResolvedSource;
import org.zipp.ai.domain.retrieval.ResolvedSourceSet;

import java.util.List;

@Mapper
public interface IRequestSourceSnapshotMapper {
    int insertSnapshot(@Param("owner") CatalogOwner owner, @Param("runId") String runId,
                       @Param("fingerprint") String fingerprint,
                       @Param("sources") ResolvedSourceSet sources);
    int insertSnapshotItem(@Param("runId") String runId, @Param("ordinal") int ordinal,
                           @Param("source") ResolvedSource source);
    RequestSourceSnapshotPO selectSnapshot(@Param("owner") CatalogOwner owner,
                                           @Param("runId") String runId);
    List<RequestSourceSnapshotItemPO> selectSnapshotItems(@Param("owner") CatalogOwner owner,
                                                         @Param("runId") String runId);
}
