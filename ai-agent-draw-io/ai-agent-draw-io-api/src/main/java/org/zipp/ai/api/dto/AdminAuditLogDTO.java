package org.zipp.ai.api.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class AdminAuditLogDTO {

    private String id;
    private String actorUserId;
    private String action;
    private String targetType;
    private String targetId;
    private String outcome;
    private String ipAddress;
    private String userAgent;
    private Instant createdAt;
}
