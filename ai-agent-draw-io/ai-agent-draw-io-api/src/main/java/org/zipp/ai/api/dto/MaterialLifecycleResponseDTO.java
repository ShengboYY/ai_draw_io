package org.zipp.ai.api.dto;

import java.time.Instant;

public record MaterialLifecycleResponseDTO(String materialId, String retentionClass,
                                           String lifecycleState, long lifecycleGeneration,
                                           Instant expiresAt, Instant trashExpiresAt) { }
