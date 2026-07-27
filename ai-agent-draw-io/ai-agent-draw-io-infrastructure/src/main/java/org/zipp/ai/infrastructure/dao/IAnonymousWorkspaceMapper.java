package org.zipp.ai.infrastructure.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.zipp.ai.infrastructure.dao.po.AnonymousWorkspacePO;

import java.util.Date;

@Mapper
public interface IAnonymousWorkspaceMapper {

    int insert(AnonymousWorkspacePO workspace);

    AnonymousWorkspacePO selectByCredentialId(@Param("credentialId") String credentialId);

    /** Claims only an ACTIVE credential so concurrent or repeated claims cannot change ownership. */
    int markClaimed(@Param("credentialId") String credentialId,
                    @Param("claimedByUserId") String claimedByUserId,
                    @Param("claimedAt") Date claimedAt);
}
