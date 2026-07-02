package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.ModelCredentialPO;

import java.util.Date;
import java.util.List;

@Mapper
public interface IModelCredentialMapper {

    int insert(ModelCredentialPO credential);

    List<ModelCredentialPO> selectByUserId(@Param("userId") String userId);

    int disable(@Param("userId") String userId,
                @Param("id") String id,
                @Param("disabledAt") Date disabledAt);

    int delete(@Param("userId") String userId,
               @Param("id") String id,
               @Param("deletedAt") Date deletedAt);
}
