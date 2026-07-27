package org.zipp.ai.domain.material.model.valobj;

import java.time.Instant;

public record MaterialDeletionPreview(MaterialDeletionImpact impact,
                                      String deletionConfirmationToken,
                                      Instant confirmationExpiresAt) {
    public MaterialDeletionPreview {
        if (impact == null || deletionConfirmationToken == null || deletionConfirmationToken.isBlank()
                || confirmationExpiresAt == null) {
            throw new IllegalArgumentException("deletion preview is incomplete");
        }
    }
}
