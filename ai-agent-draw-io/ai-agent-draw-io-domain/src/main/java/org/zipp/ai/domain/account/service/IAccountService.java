package org.zipp.ai.domain.account.service;

import org.zipp.ai.domain.account.model.valobj.EmailVerificationResult;
import org.zipp.ai.domain.account.model.valobj.RegisterAccountCommand;
import org.zipp.ai.domain.account.model.valobj.RegistrationResult;

/**
 * Account lifecycle up to first login: registration, email verification, and resend-verification.
 * Login/session handling is intentionally out of scope here (added with Spring Security later).
 */
public interface IAccountService {

    /** Register a new email/password user as pending verification and send a verification email. */
    RegistrationResult register(RegisterAccountCommand command);

    /** Consume an email-verification token, activating the account on success. */
    EmailVerificationResult verifyEmail(String rawToken);

    /** Re-issue a verification email for a pending account. Always safe/generic for unknown emails. */
    void resendVerification(String email);

}
