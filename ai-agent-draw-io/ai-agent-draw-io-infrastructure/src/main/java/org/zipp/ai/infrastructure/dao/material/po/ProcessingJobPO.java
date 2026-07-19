package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class ProcessingJobPO {
    private String id;
    private String uploadSessionId;
    private String revisionId;
    private String stage;
    private String workKey;
    private String inputFingerprint;
    private int priority;
    private String status;
    private int attempt;
    private Instant notBefore;
    private String leaseOwner;
    private Instant leaseUntil;
    private long fenceToken;
    private String lastErrorCode;
    private String payloadJson;
}
