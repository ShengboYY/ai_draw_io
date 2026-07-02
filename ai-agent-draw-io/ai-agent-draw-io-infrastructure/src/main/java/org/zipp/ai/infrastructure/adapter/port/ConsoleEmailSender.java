package org.zipp.ai.infrastructure.adapter.port;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.account.adapter.port.IEmailSender;

/**
 * Local-development email sender: prints the verification/reset URL to the log so developers can
 * click through without configuring SMTP or SES. Enabled by default; disable by setting
 * {@code account.email.sender=ses} once an SES adapter is added.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(IEmailSender.class)
@ConditionalOnProperty(value = "account.email.sender", havingValue = "console", matchIfMissing = true)
public class ConsoleEmailSender implements IEmailSender {

    @Override
    public void sendVerificationEmail(String email, String verificationUrl) {
        // The URL contains the raw token; safe to log ONLY for local dev/console adapter.
        log.info("[ConsoleEmailSender] Verification link for {} => {}", email, verificationUrl);
    }

    @Override
    public void sendPasswordResetEmail(String email, String resetUrl) {
        log.info("[ConsoleEmailSender] Password-reset link for {} => {}", email, resetUrl);
    }
}
