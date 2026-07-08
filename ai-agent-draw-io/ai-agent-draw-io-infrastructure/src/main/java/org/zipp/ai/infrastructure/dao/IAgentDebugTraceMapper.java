package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.DebugTraceCapturePO;
import org.zipp.ai.infrastructure.dao.po.DebugTraceControlPO;

import java.util.Date;
import java.util.List;

@Mapper
public interface IAgentDebugTraceMapper {

    int insertControl(DebugTraceControlPO control);

    List<DebugTraceControlPO> listEnabledControls();

    int insertCapture(DebugTraceCapturePO capture);

    List<DebugTraceCapturePO> listCapturesByRunId(@Param("runId") String runId);

    int deleteExpiredContent(@Param("now") Date now);

    int extendRunContentExpiry(@Param("runId") String runId,
                               @Param("expiresAt") Date expiresAt);

    int deleteContentForUser(@Param("userId") String userId,
                             @Param("anonymizedUserId") String anonymizedUserId,
                             @Param("deletedAt") Date deletedAt);

    int redactControlsForUser(@Param("userId") String userId,
                              @Param("anonymizedUserId") String anonymizedUserId,
                              @Param("deletedAt") Date deletedAt);
}
