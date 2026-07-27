package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.*;

import java.util.List;

@Mapper
public interface IMaterialPreviewMapper {
    List<MaterialPageCatalogPO> selectPages(@Param("ownerType") String ownerType,
                                            @Param("ownerKey") String ownerKey,
                                            @Param("materialId") String materialId,
                                            @Param("versionId") String versionId,
                                            @Param("revisionId") String revisionId);
    MaterialPreviewArtifactPO selectPreviewArtifact(@Param("ownerType") String ownerType,
                                                     @Param("ownerKey") String ownerKey,
                                                     @Param("materialId") String materialId,
                                                     @Param("versionId") String versionId,
                                                     @Param("revisionId") String revisionId,
                                                     @Param("pageNo") int pageNo);
    MaterialReprocessSourcePO selectReprocessSource(@Param("ownerType") String ownerType,
                                                     @Param("ownerKey") String ownerKey,
                                                     @Param("materialId") String materialId);
    MaterialReprocessSourcePO selectReprocessSourceForUpdate(@Param("ownerType") String ownerType,
                                                              @Param("ownerKey") String ownerKey,
                                                              @Param("materialId") String materialId);
    MaterialReprocessResultPO selectRequest(@Param("ownerKey") String ownerKey,
                                            @Param("materialId") String materialId,
                                            @Param("requestFingerprint") String requestFingerprint);
    MaterialReprocessResultPO selectByProcessingFingerprint(@Param("ownerKey") String ownerKey,
                                                            @Param("versionId") String versionId,
                                                            @Param("processingFingerprint") String processingFingerprint);
    int restartFailedRevision(@Param("revisionId") String revisionId,
                              @Param("state") String state);
    int insertRequest(@Param("ownerKey") String ownerKey,
                      @Param("materialId") String materialId,
                      @Param("requestFingerprint") String requestFingerprint,
                      @Param("versionId") String versionId,
                      @Param("revisionId") String revisionId);
    MaterialReprocessResultPO selectReprocessResult(@Param("ownerKey") String ownerKey,
                                                     @Param("revisionId") String revisionId);
}
