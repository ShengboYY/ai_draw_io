package org.zipp.ai.infrastructure.dao.material;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.material.po.DocumentExtractionWorkPO;
import org.zipp.ai.infrastructure.dao.material.po.MaterialPagePO;
import org.zipp.ai.infrastructure.dao.material.po.RevisionArtifactPO;
import org.zipp.ai.infrastructure.dao.material.po.RevisionPageWorkPO;
import org.zipp.ai.infrastructure.dao.material.po.ProcessingGenerationPO;

import java.util.List;

@Mapper
public interface IDocumentProcessingMapper {
    DocumentExtractionWorkPO selectExtractionWork(@Param("revisionId") String revisionId,
                                                   @Param("jobId") String jobId,
                                                   @Param("workerId") String workerId,
                                                   @Param("fenceToken") long fenceToken);
    ProcessingGenerationPO selectProcessingGenerations(@Param("revisionId") String revisionId,
                                                       @Param("jobId") String jobId,
                                                       @Param("workerId") String workerId,
                                                       @Param("fenceToken") long fenceToken);
    List<RevisionPageWorkPO> selectPageBatch(@Param("revisionId") String revisionId,
                                             @Param("jobId") String jobId,
                                             @Param("workerId") String workerId,
                                             @Param("fenceToken") long fenceToken);
    int countCurrentFence(@Param("revisionId") String revisionId,
                          @Param("expectedGeneration") long expectedGeneration,
                          @Param("materialLifecycleGeneration") long materialLifecycleGeneration,
                          @Param("expectedStage") String expectedStage,
                          @Param("jobId") String jobId,
                          @Param("workerId") String workerId,
                          @Param("fenceToken") long fenceToken);
    int insertPage(MaterialPagePO page);
    MaterialPagePO selectPage(@Param("revisionId") String revisionId, @Param("pageNo") int pageNo);
    int insertArtifact(RevisionArtifactPO artifact);
    RevisionArtifactPO selectArtifact(@Param("revisionId") String revisionId,
                                      @Param("pageNo") int pageNo,
                                      @Param("artifactKind") String artifactKind);
    int updatePageOcr(@Param("revisionId") String revisionId,
                      @Param("pageNo") int pageNo,
                      @Param("ocrStatus") String ocrStatus,
                      @Param("ocrQuality") double ocrQuality,
                      @Param("rawExtractionKey") String rawExtractionKey);
    int updatePageCanonical(@Param("revisionId") String revisionId,
                            @Param("pageNo") int pageNo,
                            @Param("canonicalPageKey") String canonicalPageKey);
    int countMissingCanonical(@Param("revisionId") String revisionId);
    List<String> selectCanonicalHashes(@Param("revisionId") String revisionId);
    int updatePageCount(@Param("revisionId") String revisionId, @Param("pageCount") int pageCount);
    int updateRevisionProgress(@Param("revisionId") String revisionId,
                               @Param("stage") String stage,
                               @Param("progress") int progress);
    int advanceRevision(@Param("revisionId") String revisionId,
                        @Param("expectedGeneration") long expectedGeneration,
                        @Param("stage") String stage,
                        @Param("progress") int progress);
}
