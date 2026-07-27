package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.ContentBlobPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialScopeLinkPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialVersionMatchPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialVersionPO;
import org.zipp.ai.infrastructure.dao.material.po.OriginalPromotionWorkPO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingRevisionPO;
import org.zipp.ai.infrastructure.dao.material.po.UploadSessionPO;

@Mapper
public interface IMaterializationMapper {
    UploadSessionPO selectUploadForMaterialization(@Param("uploadId") String uploadId,
                                                   @Param("jobId") String jobId,
                                                   @Param("workerId") String workerId,
                                                   @Param("fenceToken") long fenceToken);
    int insertContentBlob(ContentBlobPO blob);
    ContentBlobPO selectContentBlobForUpdate(@Param("ownerType") String ownerType,
                                             @Param("ownerKey") String ownerKey,
                                             @Param("contentSha256") String contentSha256,
                                             @Param("byteSize") long byteSize);
    int reviveDeletedBlob(ContentBlobPO blob);
    MaterialVersionMatchPO selectReusableVersionForUpdate(@Param("ownerType") String ownerType,
                                                           @Param("ownerKey") String ownerKey,
                                                           @Param("contentBlobId") String contentBlobId);
    MaterialVersionMatchPO selectTargetVersionForUpdate(@Param("materialId") String materialId,
                                                         @Param("ownerType") String ownerType,
                                                         @Param("ownerKey") String ownerKey,
                                                         @Param("contentBlobId") String contentBlobId);
    MaterialPO selectActiveMaterialForUpdate(@Param("materialId") String materialId,
                                              @Param("ownerType") String ownerType,
                                              @Param("ownerKey") String ownerKey);
    int selectNextVersionNo(@Param("materialId") String materialId);
    int insertMaterial(MaterialPO material);
    int insertScopeLink(MaterialScopeLinkPO scopeLink);
    int persistRetainedMaterial(@Param("materialId") String materialId,
                                @Param("ownerType") String ownerType,
                                @Param("ownerKey") String ownerKey,
                                @Param("expectedGeneration") long expectedGeneration,
                                @Param("newGeneration") long newGeneration);
    int insertVersion(MaterialVersionPO version);
    int insertRevision(ProcessingRevisionPO revision);
    int updateLatestVersion(@Param("materialId") String materialId,
                            @Param("versionId") String versionId);
    int correlateForPromotion(@Param("uploadId") String uploadId,
                              @Param("expectedGeneration") long expectedGeneration,
                              @Param("materialId") String materialId,
                              @Param("versionId") String versionId,
                              @Param("contentBlobId") String contentBlobId,
                              @Param("revisionId") String revisionId,
                              @Param("materialLifecycleGeneration") long materialLifecycleGeneration,
                              @Param("jobId") String jobId,
                              @Param("workerId") String workerId,
                              @Param("fenceToken") long fenceToken);
    int completeWithoutPromotion(@Param("uploadId") String uploadId,
                                 @Param("expectedGeneration") long expectedGeneration,
                                 @Param("materialId") String materialId,
                                 @Param("versionId") String versionId,
                                 @Param("contentBlobId") String contentBlobId,
                                 @Param("revisionId") String revisionId,
                                 @Param("materialLifecycleGeneration") long materialLifecycleGeneration,
                                 @Param("jobId") String jobId,
                                 @Param("workerId") String workerId,
                                 @Param("fenceToken") long fenceToken);
    OriginalPromotionWorkPO selectPromotionWork(@Param("uploadId") String uploadId,
                                                 @Param("jobId") String jobId,
                                                 @Param("workerId") String workerId,
                                                 @Param("fenceToken") long fenceToken);
    int pinBlobIfPromoting(@Param("uploadId") String uploadId,
                           @Param("contentBlobId") String contentBlobId,
                           @Param("objectKey") String objectKey,
                           @Param("objectVersionId") String objectVersionId,
                           @Param("eTag") String eTag,
                           @Param("checksumSha256") String checksumSha256,
                           @Param("jobId") String jobId,
                           @Param("workerId") String workerId,
                           @Param("fenceToken") long fenceToken);
    int countFixedBlobForPromotion(@Param("uploadId") String uploadId,
                                   @Param("contentBlobId") String contentBlobId,
                                   @Param("objectKey") String objectKey,
                                   @Param("objectVersionId") String objectVersionId,
                                   @Param("checksumSha256") String checksumSha256,
                                   @Param("jobId") String jobId,
                                   @Param("workerId") String workerId,
                                   @Param("fenceToken") long fenceToken);
    int markVersionProcessing(@Param("uploadId") String uploadId,
                              @Param("versionId") String versionId,
                              @Param("jobId") String jobId,
                              @Param("workerId") String workerId,
                              @Param("fenceToken") long fenceToken);
    int countProcessableVersionForPromotion(@Param("uploadId") String uploadId,
                                             @Param("versionId") String versionId,
                                             @Param("revisionId") String revisionId,
                                             @Param("jobId") String jobId,
                                             @Param("workerId") String workerId,
                                             @Param("fenceToken") long fenceToken);
    int completePromotedUpload(@Param("uploadId") String uploadId,
                               @Param("expectedGeneration") long expectedGeneration,
                               @Param("jobId") String jobId,
                               @Param("workerId") String workerId,
                               @Param("fenceToken") long fenceToken);
}
