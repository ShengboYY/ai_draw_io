package org.zipp.ai.domain.admin.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class AdminAuditLogService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final IAdminAuditLogStore auditLogStore;
    private final Clock clock;

    @Autowired
    public AdminAuditLogService(IAdminAuditLogStore auditLogStore) {
        this(auditLogStore, Clock.systemUTC());
    }

    public AdminAuditLogService(IAdminAuditLogStore auditLogStore, Clock clock) {
        this.auditLogStore = auditLogStore;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public void record(String actorUserId,
                       String action,
                       String targetType,
                       String targetId,
                       String outcome,
                       String ipAddress,
                       String userAgent) {
        if (auditLogStore == null || StringUtils.isBlank(actorUserId) || StringUtils.isBlank(action)) {
            return;
        }
        auditLogStore.insert(AdminAuditLog.builder()
                .id("aal_" + UUID.randomUUID())
                .actorUserId(actorUserId)
                .action(StringUtils.left(action, 64))
                .targetType(StringUtils.left(blankToNull(targetType), 32))
                .targetId(StringUtils.left(blankToNull(targetId), 128))
                .outcome(StringUtils.left(StringUtils.defaultIfBlank(outcome, "SUCCESS"), 24))
                .ipAddress(StringUtils.left(blankToNull(ipAddress), 64))
                .userAgent(StringUtils.left(blankToNull(userAgent), 256))
                .createdAt(clock.instant())
                .build());
    }

    public List<AdminAuditLog> listRecent(Integer requestedLimit) {
        if (auditLogStore == null) {
            return List.of();
        }
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));
        return auditLogStore.listRecent(limit);
    }

    private String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value;
    }
}
