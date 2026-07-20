package org.zipp.ai.infrastructure.dao.grounding;

import lombok.Data;

@Data
public class GroundedRunRowPO {
    private String runId;
    private String ownerKey;
    private String requestId;
    private String state;
    private Long generation;
}
