package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

@Data
public class AdminAuditLogPO {

    private String id;
    private String actorUserId;
    private String action;
    private String targetType;
    private String targetId;
    private String outcome;
    private String ipAddress;
    private String userAgent;
    private Date createdAt;
}
