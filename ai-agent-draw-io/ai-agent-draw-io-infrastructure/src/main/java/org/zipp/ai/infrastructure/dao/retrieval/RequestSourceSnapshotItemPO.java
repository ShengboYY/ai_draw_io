package org.zipp.ai.infrastructure.dao.retrieval;

import lombok.Data;

@Data
public class RequestSourceSnapshotItemPO {
    private int ordinal;
    private String materialId;
    private String versionId;
    private String revisionId;
    private String kind;
    private String displayName;
    private String scopeType;
    private String scopeKey;
    private String state;
    private String origin;
    private boolean hasText;
    private boolean hasVisual;
    private boolean pinned;
    private boolean countsAsProcessingSource;
}
