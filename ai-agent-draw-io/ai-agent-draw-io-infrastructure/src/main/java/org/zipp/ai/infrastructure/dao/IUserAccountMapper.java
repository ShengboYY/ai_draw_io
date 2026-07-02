package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.UserAccountPO;

import java.util.Date;

@Mapper
public interface IUserAccountMapper {

    UserAccountPO selectByEmailNormalized(@Param("emailNormalized") String emailNormalized);

    UserAccountPO selectById(@Param("id") String id);

    int insert(UserAccountPO user);

    int markVerified(@Param("id") String id,
                     @Param("verifiedAt") Date verifiedAt);

    int updatePasswordHashAndIncrementSessionVersion(@Param("id") String id,
                                                     @Param("passwordHash") String passwordHash,
                                                     @Param("updatedAt") Date updatedAt);

}
