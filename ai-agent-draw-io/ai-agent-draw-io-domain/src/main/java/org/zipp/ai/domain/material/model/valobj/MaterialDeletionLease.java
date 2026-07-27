package org.zipp.ai.domain.material.model.valobj;

import org.zipp.ai.domain.material.model.aggregate.MaterialDeletionTask;

public record MaterialDeletionLease(MaterialDeletionTask task, long fenceToken) {
    public MaterialDeletionLease {
        if (task == null || fenceToken < 1 || task.fenceToken() != fenceToken) {
            throw new IllegalArgumentException("deletion lease is invalid");
        }
    }
}
