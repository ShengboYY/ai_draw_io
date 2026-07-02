package org.zipp.ai.domain.account.model.entity;

import lombok.Builder;
import lombok.Data;
import org.zipp.ai.domain.account.model.valobj.TokenPurpose;

import java.time.Instant;

/**
 * One-time email verification / password reset token. Only the hash of the raw token is ever stored,
 * so a database leak cannot be replayed against the verification/reset endpoints.
 */
@Data
@Builder
public class AccountToken {

    private String id;
    private String userId;
    private TokenPurpose purpose;
    private String tokenHash;
    private Instant expiresAt;
    private Instant usedAt;
    private Instant createdAt;

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return expiresAt == null || !now.isBefore(expiresAt);
    }
}
