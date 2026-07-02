package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

/** Persistence object for the {@code account_token} table. Only token hashes are stored. */
@Data
public class AccountTokenPO {
    private String id;
    private String userId;
    private String purpose;
    private String tokenHash;
    private Date expiresAt;
    private Date usedAt;
    private Date createdAt;
}
