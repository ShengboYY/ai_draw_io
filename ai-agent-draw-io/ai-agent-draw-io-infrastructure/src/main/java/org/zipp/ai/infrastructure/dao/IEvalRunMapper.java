package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.evaluation.controlplane.*;

import java.util.List;

@Mapper
public interface IEvalRunMapper {
    int insertRun(EvalRunPO value);
    int updateRun(EvalRunPO value);
    EvalRunPO selectRun(@Param("runId") String runId);
    EvalRunPO selectRunByIdempotencyKey(@Param("idempotencyKey") String key);
    List<EvalRunPO> selectRuns(@Param("limit") int limit, @Param("offset") int offset);
    int upsertEpisode(EvalEpisodePO value);
    EvalEpisodePO selectEpisode(@Param("episodeId") String episodeId);
    List<EvalEpisodePO> selectEpisodes(@Param("runId") String runId);
    int deleteGraders(@Param("episodeId") String episodeId);
    int insertGrader(EvalGraderResultPO value);
    List<EvalGraderResultPO> selectGraders(@Param("episodeId") String episodeId);
}
