package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class DeletionTaskPO {
    private String id;
    private String materialId;
    private long lifecycleGeneration;
    private String stage;
    private String status;
    private int attempt;
    private Instant notBefore;
    private String leaseOwner;
    private Instant leaseUntil;
    private long fenceToken;
    private String errorCode;
}
