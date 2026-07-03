package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

@Data
public class UsageCounterPO {

    private String counterKeyHash;
    private String counterKeyPreview;
    private String counterType;
    private Integer countValue;
    private Date expiresAt;
    private Date lockedUntil;
    private Date createdAt;
    private Date updatedAt;
}
