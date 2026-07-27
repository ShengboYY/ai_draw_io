package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class ChartbookProfilePO {
    private String chartbookId;
    private String ownerKey;
    private String chartbookStatus;
    private String profileRowId;
    private long version;
    private String instructions;
    private String goal;
    private String summary;
    private String glossaryJson;
    private String defaultStyleJson;
    private String stableConstraintsJson;
    private String profileState;
    private Instant updatedAt;
}
