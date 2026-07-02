package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;
import org.zipp.ai.domain.admin.service.IAdminAuditLogStore;
import org.zipp.ai.infrastructure.dao.IAdminAuditLogMapper;
import org.zipp.ai.infrastructure.dao.po.AdminAuditLogPO;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class AdminAuditLogRepository implements IAdminAuditLogStore {

    @Resource
    private IAdminAuditLogMapper adminAuditLogMapper;

    @Override
    public void insert(AdminAuditLog log) {
        adminAuditLogMapper.insert(toPo(log));
    }

    @Override
    public List<AdminAuditLog> listRecent(int limit) {
        return adminAuditLogMapper.listRecent(limit).stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public int redactDeletedUser(String userId, String redactedUserId) {
        return adminAuditLogMapper.redactDeletedUser(userId, redactedUserId);
    }

    private AdminAuditLogPO toPo(AdminAuditLog log) {
        AdminAuditLogPO po = new AdminAuditLogPO();
        po.setId(log.getId());
        po.setActorUserId(log.getActorUserId());
        po.setAction(log.getAction());
        po.setTargetType(log.getTargetType());
        po.setTargetId(log.getTargetId());
        po.setOutcome(log.getOutcome());
        po.setIpAddress(log.getIpAddress());
        po.setUserAgent(log.getUserAgent());
        po.setCreatedAt(toDate(log.getCreatedAt()));
        return po;
    }

    private AdminAuditLog toDomain(AdminAuditLogPO po) {
        return AdminAuditLog.builder()
                .id(po.getId())
                .actorUserId(po.getActorUserId())
                .action(po.getAction())
                .targetType(po.getTargetType())
                .targetId(po.getTargetId())
                .outcome(po.getOutcome())
                .ipAddress(po.getIpAddress())
                .userAgent(po.getUserAgent())
                .createdAt(toInstant(po.getCreatedAt()))
                .build();
    }

    private Date toDate(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
