package org.zipp.ai.domain.account.adapter.port;

/**
 * Outbound email seam. A {@code ConsoleEmailSender} prints links for local development/tests; an SES
 * adapter is added for production. Interface lives in the domain; the adapter lives in infrastructure.
 */
public interface IEmailSender {

    void sendVerificationEmail(String email, String verificationUrl);

    void sendPasswordResetEmail(String email, String resetUrl);

}
