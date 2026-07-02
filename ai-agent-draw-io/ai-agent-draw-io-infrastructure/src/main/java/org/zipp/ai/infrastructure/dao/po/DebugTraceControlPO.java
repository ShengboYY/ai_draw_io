package org.zipp.ai.infrastructure.dao.po;

import lombok.Data;

import java.util.Date;

/** Persistence object for admin debug trace controls. */
@Data
public class DebugTraceControlPO {

    private String id;
    private String createdByUserId;
    private String scopeUserId;
    private String scopeRunId;
    private Date scopeStartsAt;
    private Date scopeEndsAt;
    private Boolean enabled;
    private Date createdAt;
    private Date disabledAt;
}
