package org.zipp.ai.domain.account.model.valobj;

import lombok.Builder;
import lombok.Data;

/**
 * Outcome of a registration attempt. Callers must render the same generic response to the client for
 * every outcome so that registration cannot be used to enumerate existing accounts.
 */
@Data
@Builder
public class RegistrationResult {

    public enum Outcome {
        /** A brand new pending user was created and a verification email was sent. */
        CREATED,
        /** The email already belongs to a pending user; a fresh verification email was sent. */
        RESENT_PENDING,
        /** The email already belongs to an active/registered account; no email was sent. */
        ALREADY_REGISTERED
    }

    private Outcome outcome;
    private String userId;

}
