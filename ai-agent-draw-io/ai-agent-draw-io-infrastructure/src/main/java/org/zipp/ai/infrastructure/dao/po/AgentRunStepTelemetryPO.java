package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

@Data
public class AgentRunStepTelemetryPO {

    private String id;
    private String runId;
    private String userId;
    private String phase;
    private String status;
    private String errorClass;
    private Date startedAt;
    private Date completedAt;
    private Long latencyMs;
}
