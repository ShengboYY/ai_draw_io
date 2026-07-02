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
    private String eventType;
    private String content;
    private String contentSha256;
    private Date contentExpiresAt;
    private Date contentDeletedAt;
    private Date createdAt;
}
