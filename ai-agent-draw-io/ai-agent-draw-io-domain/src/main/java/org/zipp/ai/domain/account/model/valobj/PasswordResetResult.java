package org.zipp.ai.domain.account.model.valobj;

/** Client-safe outcome for consuming a password-reset token. */
public enum PasswordResetResult {

    SUCCESS,
    EXPIRED,
    ALREADY_USED,
    INVALID

}
