package org.zipp.ai.domain.admin.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Metadata-only audit entry for admin operations; request bodies and secrets are never stored. */
@Data
@Builder
public class AdminAuditLog {

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
