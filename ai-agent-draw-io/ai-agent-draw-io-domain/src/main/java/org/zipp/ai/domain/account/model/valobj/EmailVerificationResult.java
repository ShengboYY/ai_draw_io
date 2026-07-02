package org.zipp.ai.domain.account.model.valobj;

/**
 * Outcome of consuming an email-verification token. Every non-success outcome must fail safely: the
 * account is not activated and no detail beyond the outcome is leaked to the caller.
 */
public enum EmailVerificationResult {

    /** Token was valid and unused; the account is now active. */
    SUCCESS,
    /** No matching token (wrong, malformed, or never issued). */
    INVALID,
    /** Token matched but has passed its 30-minute expiry. */
    EXPIRED,
    /** Token matched but was already consumed; reuse is rejected. */
    ALREADY_USED

}
