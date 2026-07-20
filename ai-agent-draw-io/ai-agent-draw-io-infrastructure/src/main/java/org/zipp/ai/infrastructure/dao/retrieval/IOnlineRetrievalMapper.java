package org.zipp.ai.infrastructure.dao.retrieval;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.domain.retrieval.port.AuthorizedSource;
import org.zipp.ai.infrastructure.dao.retrieval.po.OnlineCandidatePO;
import org.zipp.ai.infrastructure.dao.retrieval.po.OnlineSourcePO;

import java.util.List;

@Mapper
public interface IOnlineRetrievalMapper {
    List<OnlineSourcePO> selectExplicitSources(@Param("ownerType") String ownerType,
                                               @Param("ownerKey") String ownerKey,
                                               @Param("diagramId") String diagramId,
                                               @Param("conversationId") String conversationId,
                                               @Param("versionIds") List<String> versionIds);
    List<OnlineSourcePO> selectAutomaticSources(@Param("ownerType") String ownerType,
                                                @Param("ownerKey") String ownerKey,
                                                @Param("diagramId") String diagramId,
                                                @Param("conversationId") String conversationId,
                                                @Param("limit") int limit);
    Integer countPendingConversationUploads(@Param("ownerKey") String ownerKey,
                                            @Param("conversationId") String conversationId);
    List<OnlineCandidatePO> lexicalSearch(@Param("ownerType") String ownerType,
                                          @Param("ownerKey") String ownerKey,
                                          @Param("query") String query,
                                          @Param("sources") List<AuthorizedSource> sources,
                                          @Param("includeText") boolean includeText,
                                          @Param("includeVisual") boolean includeVisual,
                                          @Param("limit") int limit);
    List<OnlineCandidatePO> resolveVectorCandidates(@Param("ownerType") String ownerType,
                                                    @Param("ownerKey") String ownerKey,
                                                    @Param("vectorIds") List<String> vectorIds,
                                                    @Param("sources") List<AuthorizedSource> sources);
    List<OnlineCandidatePO> reauthorizeCandidates(@Param("ownerType") String ownerType,
                                                  @Param("ownerKey") String ownerKey,
                                                  @Param("chunkIds") List<String> chunkIds,
                                                  @Param("sources") List<AuthorizedSource> sources,
                                                  @Param("limit") int limit);
}
