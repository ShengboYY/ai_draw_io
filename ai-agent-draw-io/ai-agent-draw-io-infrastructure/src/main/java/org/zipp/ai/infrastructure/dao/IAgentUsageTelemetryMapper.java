package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.AgentRunStepTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.AgentRunTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.AgentUsageSummaryPO;
import org.zipp.ai.infrastructure.dao.po.LlmCallTelemetryPO;
import org.zipp.ai.infrastructure.dao.po.ToolCallTelemetryPO;

import java.util.Date;

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

    AgentUsageSummaryPO summarizeByUserId(@Param("userId") String userId);
}
