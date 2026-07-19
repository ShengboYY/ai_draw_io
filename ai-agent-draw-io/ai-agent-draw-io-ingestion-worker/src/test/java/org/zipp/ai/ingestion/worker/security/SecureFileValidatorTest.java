package org.zipp.ai.ingestion.worker.security;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
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

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecureFileValidatorTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

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
