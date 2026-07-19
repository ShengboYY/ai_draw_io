package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

import java.time.Instant;

@Data
public class MaterialPO {
    private String id;
    private String ownerType;
    private String ownerKey;
    private String kind;
    private String displayName;
    private String retentionClass;
    private String originConversationId;
    private String lifecycleState;
    private long lifecycleGeneration;
    private String latestVersionId;
    private Instant lastMeaningfulActivityAt;
    private Instant expiresAt;
    private Instant trashExpiresAt;
    private Instant deletedAt;
}
