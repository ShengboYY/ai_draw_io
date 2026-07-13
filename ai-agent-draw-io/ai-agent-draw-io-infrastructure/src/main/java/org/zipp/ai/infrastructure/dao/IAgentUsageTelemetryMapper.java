package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.AgentRunStepTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.AgentRunTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.AgentTraceEventPO;
import org.zipp.ai.infrastructure.dao.po.AgentUsageSummaryPO;
import org.zipp.ai.infrastructure.dao.po.AdminUsageSummaryPO;
import org.zipp.ai.infrastructure.dao.po.DiagramTraceSnapshotPO;
import org.zipp.ai.infrastructure.dao.po.LlmCallTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.ToolCallTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.UsageDimensionSummaryPO;

import java.util.Date;
import java.util.List;

@Mapper
public interface IAgentUsageTelemetryMapper {

    int insertRun(AgentRunTelemetryPO run);

    int completeRun(@Param("id") String id,
                    @Param("status") String status,
                    @Param("errorClass") String errorClass,
                    @Param("completedAt") Date completedAt,
                    @Param("latencyMs") long latencyMs);

    int insertStep(AgentRunStepTelemetryPO step);

    int insertLlmCall(LlmCallTelemetryPO call);

    int insertToolCall(ToolCallTelemetryPO call);

    int insertTraceEvent(AgentTraceEventPO event);

    int insertDiagramSnapshot(DiagramTraceSnapshotPO snapshot);

    int backfillDiagramSnapshotThumbnail(@Param("diagramId") String diagramId,
                                         @Param("canvasHash") String canvasHash,
                                         @Param("thumbnailUrl") String thumbnailUrl);

    AgentUsageSummaryPO summarizeByUserId(@Param("userId") String userId);

    AdminUsageSummaryPO summarizeGlobal();

    List<UsageDimensionSummaryPO> summarizeByProviderModelCredentialSource();

    AgentRunTelemetryPO selectRunById(@Param("runId") String runId);

    List<AgentRunTelemetryPO> selectRuns(@Param("status") String status,
                                         @Param("userId") String userId,
                                         @Param("agentId") String agentId,
                                         @Param("limit") int limit,
                                         @Param("offset") int offset);

    List<AgentRunTelemetryPO> selectTerminalRunsAtOrBefore(@Param("snapshot") Date snapshot,
                                                           @Param("limit") int limit);

    List<AgentRunStepTelemetryPO> selectStepsByRunId(@Param("runId") String runId);

    List<LlmCallTelemetryPO> selectLlmCallsByRunId(@Param("runId") String runId);

    List<ToolCallTelemetryPO> selectToolCallsByRunId(@Param("runId") String runId);

    List<AgentTraceEventPO> selectTraceEventsByRunId(@Param("runId") String runId);

    List<DiagramTraceSnapshotPO> selectDiagramSnapshotsByRunId(@Param("runId") String runId);

    int anonymizeRuns(@Param("userId") String userId,
                      @Param("anonymizedUserId") String anonymizedUserId);

    int anonymizeSteps(@Param("userId") String userId,
                       @Param("anonymizedUserId") String anonymizedUserId);

    int anonymizeLlmCalls(@Param("userId") String userId,
                          @Param("anonymizedUserId") String anonymizedUserId);

    int anonymizeToolCalls(@Param("userId") String userId,
                           @Param("anonymizedUserId") String anonymizedUserId);

    int anonymizeTraceEvents(@Param("userId") String userId,
                             @Param("anonymizedUserId") String anonymizedUserId);

    List<String> selectExpiredRunIds(@Param("cutoff") Date cutoff,
                                     @Param("limit") int limit);

    int deleteTraceEventsByRunIds(@Param("runIds") List<String> runIds);

    int deleteDiagramSnapshotsByRunIds(@Param("runIds") List<String> runIds);

    int deleteToolCallsByRunIds(@Param("runIds") List<String> runIds);

    int deleteLlmCallsByRunIds(@Param("runIds") List<String> runIds);

    int deleteStepsByRunIds(@Param("runIds") List<String> runIds);

    int deleteRunsByIds(@Param("runIds") List<String> runIds);
}
