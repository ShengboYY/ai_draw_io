package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.AdminAuditLogPO;

import java.util.List;

@Mapper
public interface IAdminAuditLogMapper {

    int insert(AdminAuditLogPO log);

    List<AdminAuditLogPO> listRecent(@Param("limit") int limit);
}
