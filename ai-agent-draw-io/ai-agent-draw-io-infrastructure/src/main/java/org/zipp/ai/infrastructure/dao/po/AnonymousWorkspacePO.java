package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

/** Persistence representation of an anonymous workspace. Raw credential secrets never reach it. */
@Data
public class AnonymousWorkspacePO {
    private String ownerId;
    private String credentialId;
    private String credentialHash;
    private String status;
    private String claimedByUserId;
    private Date createdAt;
    private Date claimedAt;
}
