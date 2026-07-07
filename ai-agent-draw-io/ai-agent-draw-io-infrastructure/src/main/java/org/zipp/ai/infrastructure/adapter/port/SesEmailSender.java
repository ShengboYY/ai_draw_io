package org.zipp.ai.infrastructure.adapter.port;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.zipp.ai.domain.account.adapter.port.IEmailSender;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

@Component
@ConditionalOnProperty(value = "account.email.sender", havingValue = "ses")
public class SesEmailSender implements IEmailSender {

    private static final String CHARSET = "UTF-8";

    private final SesV2Client sesClient;
    private final String fromAddress;
    private final String replyToAddress;

    public SesEmailSender(SesV2Client sesClient,
                          @Value("${account.email.from}") String fromAddress,
                          @Value("${account.email.reply-to:}") String replyToAddress) {
        if (!StringUtils.hasText(fromAddress)) {
            throw new IllegalStateException("account.email.from must be configured when account.email.sender=ses");
        }
        this.sesClient = sesClient;
        this.fromAddress = fromAddress.trim();
        this.replyToAddress = StringUtils.hasText(replyToAddress) ? replyToAddress.trim() : "";
    }

    @Override
    public void sendVerificationEmail(String email, String verificationUrl) {
        sendEmail(
                email,
                "Verify your FreeDraw AI email",
                "Welcome to FreeDraw AI.\n\nVerify your email with this link:\n" + verificationUrl + "\n\n"
                        + "If you did not create this account, you can ignore this email.",
                "<p>Welcome to FreeDraw AI.</p>"
                        + "<p>Verify your email with this link:</p>"
                        + "<p><a href=\"" + htmlEscape(verificationUrl) + "\">Verify email</a></p>"
                        + "<p>If you did not create this account, you can ignore this email.</p>");
    }

    @Override
    public void sendPasswordResetEmail(String email, String resetUrl) {
        sendEmail(
                email,
                "Reset your FreeDraw AI password",
                "Reset your FreeDraw AI password with this link:\n" + resetUrl + "\n\n"
                        + "If you did not request this, you can ignore this email.",
                "<p>Reset your FreeDraw AI password with this link:</p>"
                        + "<p><a href=\"" + htmlEscape(resetUrl) + "\">Reset password</a></p>"
                        + "<p>If you did not request this, you can ignore this email.</p>");
    }

    private void sendEmail(String recipient, String subject, String textBody, String htmlBody) {
        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .fromEmailAddress(fromAddress)
                .destination(Destination.builder().toAddresses(recipient).build())
                .content(EmailContent.builder()
                        .simple(Message.builder()
                                .subject(Content.builder().charset(CHARSET).data(subject).build())
                                .body(Body.builder()
                                        .text(Content.builder().charset(CHARSET).data(textBody).build())
                                        .html(Content.builder().charset(CHARSET).data(htmlBody).build())
                                        .build())
                                .build())
                        .build());

        List<String> replyTo = replyToAddresses();
        if (!replyTo.isEmpty()) {
            request.replyToAddresses(replyTo);
        }

        // Do not log recipient addresses or one-time links; SES errors bubble up to the caller.
        sesClient.sendEmail(request.build());
    }

    private List<String> replyToAddresses() {
        if (!StringUtils.hasText(replyToAddress)) {
            return List.of();
        }
        List<String> addresses = new ArrayList<>();
        for (String candidate : replyToAddress.split("[,;\\s]+")) {
            if (StringUtils.hasText(candidate)) {
                addresses.add(candidate.trim());
            }
        }
        return addresses;
    }

    private static String htmlEscape(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
