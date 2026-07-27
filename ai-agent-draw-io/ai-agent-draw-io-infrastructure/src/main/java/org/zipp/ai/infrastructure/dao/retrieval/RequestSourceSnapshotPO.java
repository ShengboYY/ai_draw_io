package org.zipp.ai.infrastructure.dao.retrieval;

import lombok.Data;

@Data
public class RequestSourceSnapshotPO {
    private String runId;
    private String declarationFingerprint;
    private String sourceMode;
    private int processingSourceCount;
    private int unavailableSourceCount;
}
