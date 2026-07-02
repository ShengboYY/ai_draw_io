package org.zipp.ai.domain.admin.service;

import org.zipp.ai.domain.admin.model.entity.AdminAuditLog;

import java.util.List;

public interface IAdminAuditLogStore {

    void insert(AdminAuditLog log);

    List<AdminAuditLog> listRecent(int limit);
}
