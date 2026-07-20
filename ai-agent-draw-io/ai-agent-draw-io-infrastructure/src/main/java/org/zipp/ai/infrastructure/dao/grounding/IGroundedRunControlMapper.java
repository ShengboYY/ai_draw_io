package org.zipp.ai.infrastructure.dao.grounding;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.domain.grounding.port.GroundedRunControlPort;

@Mapper
public interface IGroundedRunControlMapper {
    int insertRun(@Param("identity") GroundedRunControlPort.RunIdentity identity);
    GroundedRunRowPO selectByRequest(@Param("identity") GroundedRunControlPort.RunIdentity identity);
    int cancelRun(@Param("identity") GroundedRunControlPort.RunIdentity identity);
}
