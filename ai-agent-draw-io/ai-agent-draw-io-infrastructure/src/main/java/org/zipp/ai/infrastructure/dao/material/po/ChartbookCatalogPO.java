package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class ChartbookCatalogPO {
    private String id;
    private String ownerKey;
    private String name;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
