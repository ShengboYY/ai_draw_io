package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.UsageCounterPO;

import java.util.Date;

@Mapper
public interface IUsageCounterMapper {

    int insertEmptyIfAbsent(@Param("counterKeyHash") String counterKeyHash,
                            @Param("counterKeyPreview") String counterKeyPreview,
                            @Param("counterType") String counterType,
                            @Param("expiresAt") Date expiresAt,
                            @Param("now") Date now);

    UsageCounterPO selectByKeyHash(@Param("counterKeyHash") String counterKeyHash);

    UsageCounterPO selectByKeyHashForUpdate(@Param("counterKeyHash") String counterKeyHash);

    int updateCounter(UsageCounterPO counter);

    int deleteByKeyHash(@Param("counterKeyHash") String counterKeyHash);
}
