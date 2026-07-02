package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Deletion result intentionally exposes only the redacted user id. */
@Data
@Builder
public class AccountDeletionResult {

    private String anonymizedUserId;
    private Instant deletedAt;
}
