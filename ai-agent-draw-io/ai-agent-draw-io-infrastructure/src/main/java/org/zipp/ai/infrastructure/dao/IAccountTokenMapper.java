package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.AccountTokenPO;

import java.util.Date;

@Mapper
public interface IAccountTokenMapper {

    int insert(AccountTokenPO token);

    AccountTokenPO selectByHashAndPurpose(@Param("tokenHash") String tokenHash,
                                          @Param("purpose") String purpose);

    /** Consume a token in a single UPDATE; returns 1 only if this call flipped used_at from NULL. */
    int markUsed(@Param("id") String id,
                 @Param("usedAt") Date usedAt);

    int deleteByUserId(@Param("userId") String userId);

}
