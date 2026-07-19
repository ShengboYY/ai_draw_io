package org.zipp.ai.domain.material.service;

import java.time.Instant;

/**
 * Account-deletion seam for the material bounded context.
 * A durable implementation replaces {@link #NO_OP} before material upload is enabled.
 */
public interface MaterialDeletionModule {

    MaterialDeletionModule NO_OP = (ownerKey, deletedAt) -> { };

    void requestAccountDeletion(String ownerKey, Instant deletedAt);
}
