package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.SkillPO;

import java.util.List;

@Mapper
public interface ISkillMapper {

    List<SkillPO> selectPublic();

    List<SkillPO> selectByOwner(@Param("ownerId") String ownerId);

    int upsert(SkillPO skill);

    int deleteByOwnerAndName(@Param("ownerId") String ownerId, @Param("name") String name);
}
