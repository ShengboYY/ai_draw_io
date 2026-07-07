package org.zipp.ai.infrastructure.adapter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

public class SesEmailSenderTest {

    @Test
    public void sendsVerificationEmailThroughSes() {
        CapturingSesClient client = new CapturingSesClient();
        SesEmailSender sender = new SesEmailSender(client, "no-reply@freedrawai.com", "");

        sender.sendVerificationEmail(
                "user@example.com",
                "https://freedrawai.com/verify-email?token=abc&next=/draw");

        SendEmailRequest captured = client.lastRequest;
        String html = captured.content().simple().body().html().data();
        assertEquals("no-reply@freedrawai.com", captured.fromEmailAddress());
        assertEquals("user@example.com", captured.destination().toAddresses().get(0));
        assertEquals("Verify your FreeDraw AI email", captured.content().simple().subject().data());
        assertTrue(captured.content().simple().body().text().data()
                .contains("https://freedrawai.com/verify-email?token=abc&next=/draw"));
        assertTrue(html.contains("https://freedrawai.com/verify-email?token=abc&amp;next=/draw"));
    }

    @Test
    public void requiresFromAddressWhenSesIsEnabled() {
        assertThrows(IllegalStateException.class, () -> new SesEmailSender(new CapturingSesClient(), " ", ""));
    }

    private static class CapturingSesClient implements SesV2Client {

        private SendEmailRequest lastRequest;

        @Override
        public SendEmailResponse sendEmail(SendEmailRequest request) {
            this.lastRequest = request;
            return SendEmailResponse.builder().messageId("ses-message-id").build();
        }

        @Override
        public String serviceName() {
            return "ses";
        }

        @Override
        public void close() {
            // No resources are opened by this fake client.
        }
    }
}
