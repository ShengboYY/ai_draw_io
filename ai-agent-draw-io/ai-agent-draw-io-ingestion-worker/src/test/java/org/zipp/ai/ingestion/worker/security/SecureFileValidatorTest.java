package org.zipp.ai.ingestion.worker.security;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionRemoteGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.DownloadedQuarantineObject;
import org.zipp.ai.domain.ingestion.model.valobj.ScanResult;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.material.model.valobj.RetentionClass;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureFileValidatorTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void singleFramePngIsAcceptedAndItsPixelsAreCounted() throws Exception {
        byte[] content;
        // Generate a real PNG so this test exercises the JDK image reader configuration.
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", output);
            content = output.toByteArray();
        }
        SecureFileValidator validator = new SecureFileValidator(ignored -> new ScanResult(true, null));

        var result = validator.validate(session("diagram.png", "image/png", content), object(content));

        assertEquals("image/png", result.detectedMediaType());
        assertEquals(6L, result.pixelCount());
    }

    @Test
    void malwareIsRejectedBeforeDocumentParsing() throws Exception {
        byte[] eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        UploadSession session = session("eicar.pdf", "application/pdf", eicar);
        SecureFileValidator validator = new SecureFileValidator(
                ignored -> new ScanResult(false, UploadErrorCode.REJECTED_SECURITY.name()));

        UploadSecurityException error = assertThrows(UploadSecurityException.class,
                () -> validator.validate(session, object(eicar)));

        assertEquals(UploadErrorCode.REJECTED_SECURITY, error.errorCode());
    }

    @Test
    void pdfJavaScriptOpenActionIsRejected() throws Exception {
        byte[] content;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert('x')"));
            document.save(output);
            content = output.toByteArray();
        }
        SecureFileValidator validator = new SecureFileValidator(ignored -> new ScanResult(true, null));

        UploadSecurityException error = assertThrows(UploadSecurityException.class,
                () -> validator.validate(session("active.pdf", "application/pdf", content), object(content)));

        assertEquals(UploadErrorCode.REJECTED_SECURITY, error.errorCode());
    }

    @Test
    void ordinaryHttpAndHttpsPdfLinksAreAccepted() throws Exception {
        SecureFileValidator validator = new SecureFileValidator(ignored -> new ScanResult(true, null));
        var httpContent = pdfWithUri("http://example.com");

        var httpResult = validator.validate(
                session("http-link.pdf", "application/pdf", httpContent),
                object(httpContent));
        var httpsContent = pdfWithUri("https://example.com/profile");
        var httpsResult = validator.validate(
                session("https-link.pdf", "application/pdf", httpsContent),
                object(httpsContent));

        assertEquals("application/pdf", httpResult.detectedMediaType());
        assertEquals("application/pdf", httpsResult.detectedMediaType());
    }

    @Test
    void nonWebPdfLinksRemainRejected() {
        SecureFileValidator validator = new SecureFileValidator(ignored -> new ScanResult(true, null));

        for (String uri : List.of("file:///tmp/private.txt", "javascript:alert('x')", "http:example.com")) {
            byte[] content = pdfWithUri(uri);
            UploadSecurityException error = assertThrows(UploadSecurityException.class,
                    () -> validator.validate(session("unsafe-link.pdf", "application/pdf", content), object(content)));
            assertEquals(UploadErrorCode.REJECTED_SECURITY, error.errorCode());
        }
    }

    @Test
    void launchAndRemoteGoToActionsRemainRejected() {
        SecureFileValidator validator = new SecureFileValidator(ignored -> new ScanResult(true, null));

        for (PDAction action : List.of(new PDActionLaunch(), new PDActionRemoteGoTo())) {
            byte[] content = pdfWithAction(action);
            UploadSecurityException error = assertThrows(UploadSecurityException.class,
                    () -> validator.validate(session("unsafe-action.pdf", "application/pdf", content),
                            object(content)));
            assertEquals(UploadErrorCode.REJECTED_SECURITY, error.errorCode());
        }
    }

    @Test
    void encryptedPdfIsRejectedAsSecurityContent() throws Exception {
        byte[] content;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("owner-password", "user-password",
                    new AccessPermission()));
            document.save(output);
            content = output.toByteArray();
        }
        SecureFileValidator validator = new SecureFileValidator(ignored -> new ScanResult(true, null));

        UploadSecurityException error = assertThrows(UploadSecurityException.class,
                () -> validator.validate(session("encrypted.pdf", "application/pdf", content), object(content)));

        assertEquals(UploadErrorCode.REJECTED_SECURITY, error.errorCode());
    }

    private static byte[] pdfWithUri(String uri) {
        PDActionURI action = new PDActionURI();
        action.setURI(uri);
        return pdfWithAction(action);
    }

    private static byte[] pdfWithAction(PDAction action) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            PDAnnotationLink link = new PDAnnotationLink();
            link.setAction(action);
            page.getAnnotations().add(link);
            document.addPage(page);
            document.save(output);
            return output.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UploadSession session(String name, String mediaType, byte[] content) {
        return UploadSession.create("upl_1", OwnerType.USER, "usr_1", "idem_1", name, mediaType,
                content.length, sha256(content),
                new UploadTarget(MaterialScopeType.CONVERSATION, "conv_1", RetentionClass.TEMPORARY),
                null, "quarantine", "incoming/opaque", NOW.plusSeconds(600), NOW);
    }

    private static DownloadedQuarantineObject object(byte[] content) {
        try {
            Path path = Files.createTempFile("validator-test-", ".bin");
            Files.write(path, content);
            path.toFile().deleteOnExit();
            return new DownloadedQuarantineObject("incoming/opaque", path, content.length, sha256(content));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
