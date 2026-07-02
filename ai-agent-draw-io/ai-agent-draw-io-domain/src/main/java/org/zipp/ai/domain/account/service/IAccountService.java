package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.EmailVerificationResult;
import org.zipp.ai.domain.account.model.valobj.LoginAccountCommand;
import org.zipp.ai.domain.account.model.valobj.LoginResult;
import org.zipp.ai.domain.account.model.valobj.RegisterAccountCommand;
import org.zipp.ai.domain.account.model.valobj.RegistrationResult;

import java.util.Optional;

/**
 * Account lifecycle: registration, email verification, resend-verification, and password login. The
 * HTTP session itself is managed by Spring Security in the trigger layer; this service only decides
 * whether a login attempt should establish one and how to hydrate a session-user back into an
 * aggregate.
 */
public interface IAccountService {

    /** Register a new email/password user as pending verification and send a verification email. */
    RegistrationResult register(RegisterAccountCommand command);

    /** Consume an email-verification token, activating the account on success. */
    EmailVerificationResult verifyEmail(String rawToken);

    /** Re-issue a verification email for a pending account. Always safe/generic for unknown emails. */
    void resendVerification(String email);

    /**
     * Authenticate an email/password pair. Verified-and-active users get {@link LoginResult.Outcome#SUCCESS};
     * pending/disabled/deleted/unknown users get a specific outcome that the trigger layer maps to a
     * fixed set of client-facing statuses (never leaking whether the email exists).
     */
    LoginResult login(LoginAccountCommand command);

    /** Rehydrate an authenticated principal after a session cookie is presented. */
    Optional<UserAccount> findById(String userId);

}
