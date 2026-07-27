package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.UploadSessionPO;

import java.time.Instant;

@Mapper
public interface IUploadSessionMapper {
    UploadSessionPO selectByOwnerAndIdempotencyKey(@Param("ownerType") String ownerType,
                                                   @Param("ownerKey") String ownerKey,
                                                   @Param("idempotencyKey") String idempotencyKey);
    UploadSessionPO selectByIdForOwner(@Param("id") String id, @Param("ownerType") String ownerType,
                                      @Param("ownerKey") String ownerKey);
    UploadSessionPO selectById(@Param("id") String id);
    int insert(UploadSessionPO session);
    int pinObject(@Param("session") UploadSessionPO session, @Param("expectedGeneration") long expectedGeneration);
    int expire(@Param("id") String id, @Param("expectedGeneration") long expectedGeneration);
    long sumAccountOriginalBytes(@Param("ownerType") String ownerType, @Param("ownerKey") String ownerKey);
    int countActiveFiles(@Param("ownerType") String ownerType, @Param("ownerKey") String ownerKey);
    int countProcessing(@Param("ownerType") String ownerType, @Param("ownerKey") String ownerKey);
    int ensureRateBucket(@Param("subjectType") String subjectType, @Param("subjectKey") String subjectKey,
                         @Param("hourBucket") Instant hourBucket, @Param("expiresAt") Instant expiresAt);
    Integer selectRateBucketForUpdate(@Param("subjectType") String subjectType,
                                      @Param("subjectKey") String subjectKey,
                                      @Param("hourBucket") Instant hourBucket);
    int incrementRateBucket(@Param("subjectType") String subjectType, @Param("subjectKey") String subjectKey,
                            @Param("hourBucket") Instant hourBucket);
    int rateBucketCount(@Param("subjectType") String subjectType, @Param("subjectKey") String subjectKey,
                        @Param("hourBucket") Instant hourBucket);
    int countOwnedDiagram(@Param("ownerKey") String ownerKey, @Param("diagramId") String diagramId);
    int countOwnedChartbook(@Param("ownerKey") String ownerKey, @Param("chartbookId") String chartbookId);
    int countOwnedConversation(@Param("ownerKey") String ownerKey,
                               @Param("conversationId") String conversationId);
    int bindConversation(@Param("conversationId") String conversationId, @Param("diagramId") String diagramId,
                         @Param("ownerKey") String ownerKey);
    int countOwnedMaterial(@Param("ownerType") String ownerType, @Param("ownerKey") String ownerKey,
                           @Param("materialId") String materialId);
    int beginProcessing(@Param("id") String id, @Param("expectedGeneration") long expectedGeneration,
                        @Param("jobId") String jobId, @Param("workerId") String workerId,
                        @Param("fenceToken") long fenceToken);
    int reject(@Param("id") String id, @Param("expectedGeneration") long expectedGeneration,
               @Param("errorCode") String errorCode, @Param("jobId") String jobId,
               @Param("workerId") String workerId, @Param("fenceToken") long fenceToken);
    int commitSecurityValidation(@Param("id") String id, @Param("expectedGeneration") long expectedGeneration,
                                 @Param("actualSize") long actualSize, @Param("actualSha256") String actualSha256,
                                 @Param("detectedMime") String detectedMime,
                                 @Param("pageCount") Integer pageCount, @Param("pixelCount") Long pixelCount,
                                 @Param("jobId") String jobId, @Param("workerId") String workerId,
                                 @Param("fenceToken") long fenceToken);
}
