package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.evaluation.TraceAnalysisItemPO;
import org.zipp.ai.infrastructure.dao.po.evaluation.TraceAnalysisJobPO;

import java.util.List;

@Mapper
public interface ITraceAnalysisMapper {
    TraceAnalysisJobPO selectJob(@Param("jobId") String jobId);
    TraceAnalysisJobPO selectJobByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
    List<TraceAnalysisJobPO> selectJobs(@Param("limit") int limit);
    List<TraceAnalysisItemPO> selectItems(@Param("jobId") String jobId);
    int insertJobIfAbsent(TraceAnalysisJobPO job);
    int insertItem(TraceAnalysisItemPO item);
    int updateJob(TraceAnalysisJobPO job);
    int updateItem(TraceAnalysisItemPO item);
}
