package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

/** Persistence object for debug trace captures; content can be nulled on expiry. */
@Data
public class DebugTraceCapturePO {

    private String id;
    private String controlId;
    private String userId;
    private String runId;
    private String spanId;
    private String eventType;
    private String payloadKind;
    private String contentType;
    private String content;
    private String contentSha256;
    private Long originalLength;
    private Boolean truncated;
    private Date contentExpiresAt;
    private Date contentDeletedAt;
    private Date createdAt;
}
