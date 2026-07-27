package org.zipp.ai.domain.material.model.valobj;

import org.zipp.ai.domain.material.model.aggregate.Material;

import java.time.Instant;

public record MaterialLifecycleResult(String materialId, RetentionClass retentionClass,
                                      MaterialLifecycleState lifecycleState,
                                      long lifecycleGeneration, Instant expiresAt,
                                      Instant trashExpiresAt) {
    public static MaterialLifecycleResult from(Material material) {
        return new MaterialLifecycleResult(material.id(), material.retentionClass(),
                material.lifecycleState(), material.lifecycleGeneration(), material.expiresAt(),
                material.trashExpiresAt());
    }
}
