package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminRunStepDTO {

    private String id;
    private String runId;
    private String parentId;
    private String userId;
    private String phase;
    private String status;
    private String errorClass;
    private Instant startedAt;
    private Instant completedAt;
    private Long latencyMs;
}
