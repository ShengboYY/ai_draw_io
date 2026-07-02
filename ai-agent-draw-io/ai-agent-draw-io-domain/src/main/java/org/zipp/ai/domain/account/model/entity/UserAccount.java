package org.zipp.ai.domain.account.model.entity;

import lombok.Builder;
import lombok.Data;
import org.zipp.ai.domain.account.model.valobj.AccountStatus;

import java.time.Instant;

/**
 * Authenticated user aggregate. Passwords are only ever held as a hash; the raw password never lives
 * on this entity. New registrations start in {@link AccountStatus#PENDING_VERIFICATION} and become
 * {@link AccountStatus#ACTIVE} once an email-verification token is consumed.
 */
@Data
@Builder
public class UserAccount {

    private String id;
    private String email;
    private String emailNormalized;
    private String passwordHash;
    private AccountStatus status;
    private int sessionVersion;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant verifiedAt;
    private Instant deletedAt;

    public boolean isPendingVerification() {
        return status == AccountStatus.PENDING_VERIFICATION;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
