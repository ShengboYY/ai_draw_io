package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class ChartbookProfileAuditPO {
    private String chartbookId;
    private String ownerKey;
    private long version;
}
