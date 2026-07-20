package org.zipp.ai.infrastructure.dao.retrieval.po;

import lombok.Data;

@Data
public class OnlineSourcePO {
    private String materialId;
    private String versionId;
    private String revisionId;
    private String kind;
    private String scopeType;
    private String scopeKey;
    private String state;
    private boolean conversationScoped;
    private boolean hasText;
    private boolean hasVisual;
    private boolean pinned;
}
