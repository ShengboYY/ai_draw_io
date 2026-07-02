package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

/** Persistence object for the {@code app_user} table. */
@Data
public class UserAccountPO {
    private String id;
    private String email;
    private String emailNormalized;
    private String passwordHash;
    private String status;
    private Integer sessionVersion;
    private Date createdAt;
    private Date updatedAt;
    private Date verifiedAt;
    private Date deletedAt;
}
