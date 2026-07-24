package org.zipp.ai.ingestion.worker.security;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.DownloadedQuarantineObject;
import org.zipp.ai.domain.ingestion.model.valobj.SecurityValidationResult;
import org.zipp.ai.domain.ingestion.model.valobj.UploadErrorCode;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

public final class SecureFileValidator {

    private static final Set<String> ACTIVE_PDF_KEYS = Set.of(
            "JS", "JavaScript", "OpenAction", "AA", "Launch", "EmbeddedFiles", "RichMedia", "GoToR", "XFA");
    private static final Set<String> ACTIVE_PDF_ACTION_TYPES = Set.of("Launch", "GoToR");
    private static final int REGISTERED_PDF_PAGES = 200;
    private static final int ANONYMOUS_PDF_PAGES = 100;
    private static final long REGISTERED_IMAGE_PIXELS = 25_000_000L;
    private static final long ANONYMOUS_IMAGE_PIXELS = 20_000_000L;
    private static final long MAX_PDF_DECODED_BYTES = 250L * 1024L * 1024L;

    private final WorkerMalwareScanner malwareScanner;
    public SecureFileValidator(WorkerMalwareScanner malwareScanner) {
        this.malwareScanner = java.util.Objects.requireNonNull(malwareScanner, "malwareScanner");
    }

    public SecurityValidationResult validate(UploadSession session, DownloadedQuarantineObject object) {
        if (object.byteSize() != session.expectedSize()) {
            reject(UploadErrorCode.REJECTED_LIMIT, "actual byte size differs from the declaration");
        }
        if (!object.contentSha256().equalsIgnoreCase(session.expectedSha256())) {
            reject(UploadErrorCode.REJECTED_SECURITY, "actual SHA-256 differs from the declaration");
        }
        if (!malwareScanner.scan(object.path()).clean()) {
            reject(UploadErrorCode.REJECTED_SECURITY, "malware scanner rejected the object");
        }
        String detected = detect(object);
        if (!compatible(session.declaredMediaType(), detected)) {
            reject(UploadErrorCode.REJECTED_FORMAT, "declared and detected media types differ");
        }
        if ("application/pdf".equals(detected)) {
            return validatePdf(session, object);
        }
        return validateImage(session, object, detected);
    }

    private SecurityValidationResult validatePdf(UploadSession session, DownloadedQuarantineObject object) {
        try (PDDocument document = Loader.loadPDF(object.path().toFile())) {
            if (document.isEncrypted()) {
                reject(UploadErrorCode.REJECTED_SECURITY, "encrypted PDFs are not accepted");
            }
            int maximumPages = session.ownerType() == org.zipp.ai.domain.account.model.valobj.OwnerType.ANONYMOUS
                    ? ANONYMOUS_PDF_PAGES : REGISTERED_PDF_PAGES;
            if (document.getNumberOfPages() < 1 || document.getNumberOfPages() > maximumPages) {
                reject(UploadErrorCode.REJECTED_LIMIT, "PDF page count exceeds the intake limit");
            }
            Set<COSBase> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            inspectPdfNode(document.getDocumentCatalog().getCOSObject(), visited);
            long decodedBytes = 0;
            long decodedLimit = Math.min(MAX_PDF_DECODED_BYTES,
                    Math.max(20L * 1024L * 1024L, object.byteSize() * 100L));
            for (var objectKey : document.getDocument().getXrefTable().keySet()) {
                COSObject indirectObject = document.getDocument().getObjectFromPool(objectKey);
                COSBase value = indirectObject.getObject();
                inspectPdfNode(value, visited);
                if (value instanceof COSStream stream) {
                    decodedBytes += decodedSize(stream, decodedLimit - decodedBytes);
                    if (decodedBytes > decodedLimit) {
                        reject(UploadErrorCode.REJECTED_LIMIT, "PDF decoded streams exceed the compression limit");
                    }
                }
            }
            return new SecurityValidationResult(object.byteSize(), object.contentSha256(), "application/pdf",
                    document.getNumberOfPages(), null);
        } catch (UploadSecurityException e) {
            throw e;
        } catch (InvalidPasswordException e) {
            reject(UploadErrorCode.REJECTED_SECURITY, "encrypted PDFs are not accepted");
            return null;
        } catch (IOException | RuntimeException e) {
            reject(UploadErrorCode.REJECTED_FORMAT, "PDF structure could not be safely parsed");
            return null;
        }
    }

    private void inspectPdfNode(COSBase node, Set<COSBase> visited) {
        if (visited.size() > 100_000) {
            reject(UploadErrorCode.REJECTED_LIMIT, "PDF object graph exceeds the structure limit");
        }
        if (node == null || !visited.add(node)) {
            return;
        }
        if (node instanceof COSObject object) {
            inspectPdfNode(object.getObject(), visited);
        } else if (node instanceof COSDictionary dictionary) {
            // Standard PDF actions store Launch and GoToR as the /S value, not as dictionary keys.
            COSName actionType = dictionary.getCOSName(COSName.S);
            if (actionType != null && ACTIVE_PDF_ACTION_TYPES.contains(actionType.getName())) {
                reject(UploadErrorCode.REJECTED_SECURITY, "active PDF content is not accepted");
            }
            for (COSName key : dictionary.keySet()) {
                String keyName = key.getName();
                if ("URI".equals(keyName)) {
                    validateWebUri(dictionary.getDictionaryObject(key));
                } else if (ACTIVE_PDF_KEYS.contains(keyName)) {
                    reject(UploadErrorCode.REJECTED_SECURITY, "active PDF content is not accepted");
                }
                inspectPdfNode(dictionary.getDictionaryObject(key), visited);
            }
        } else if (node instanceof COSArray array) {
            for (COSBase value : array) {
                inspectPdfNode(value, visited);
            }
        }
    }

    private void validateWebUri(COSBase value) {
        // Web links are inert until clicked; all other schemes remain blocked as active PDF content.
        if (!(value instanceof COSString uriValue)) {
            reject(UploadErrorCode.REJECTED_SECURITY, "PDF link target is not a safe web URI");
            return;
        }
        try {
            URI uri = URI.create(uriValue.getString().trim());
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || scheme == null || uri.getHost() == null || uri.getHost().isBlank()
                    || (!"http".equals(scheme.toLowerCase(Locale.ROOT))
                    && !"https".equals(scheme.toLowerCase(Locale.ROOT)))) {
                reject(UploadErrorCode.REJECTED_SECURITY, "only HTTP and HTTPS PDF links are accepted");
            }
        } catch (IllegalArgumentException e) {
            reject(UploadErrorCode.REJECTED_SECURITY, "PDF link target is malformed");
        }
    }

    private long decodedSize(COSStream stream, long remainingLimit) throws IOException {
        long count = 0;
        try (InputStream input = stream.createInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > remainingLimit) {
                    return count;
                }
            }
        }
        return count;
    }

    private SecurityValidationResult validateImage(UploadSession session, DownloadedQuarantineObject object,
                                                     String detected) {
        try (ImageInputStream input = ImageIO.createImageInputStream(object.path().toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                reject(UploadErrorCode.REJECTED_FORMAT, "image decoder is unavailable");
            }
            ImageReader reader = readers.next();
            try {
                // Frame counting may search backwards, so the reader cannot be configured as seek-forward-only.
                reader.setInput(input, false, true);
                if (reader.getNumImages(true) != 1) {
                    reject(UploadErrorCode.REJECTED_FORMAT, "multi-frame images are not accepted");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = Math.multiplyExact((long) width, height);
                long maximumPixels = session.ownerType() == org.zipp.ai.domain.account.model.valobj.OwnerType.ANONYMOUS
                        ? ANONYMOUS_IMAGE_PIXELS : REGISTERED_IMAGE_PIXELS;
                if (width < 1 || height < 1 || pixels > maximumPixels) {
                    reject(UploadErrorCode.REJECTED_LIMIT, "image dimensions exceed the intake limit");
                }
                return new SecurityValidationResult(object.byteSize(), object.contentSha256(), detected,
                        null, pixels);
            } finally {
                reader.dispose();
            }
        } catch (UploadSecurityException e) {
            throw e;
        } catch (IOException | ArithmeticException e) {
            reject(UploadErrorCode.REJECTED_FORMAT, "image structure could not be safely parsed");
            return null;
        }
    }

    private String detect(DownloadedQuarantineObject object) {
        // Intake accepts only three formats, so strict magic bytes are smaller and less ambiguous
        // than extension-based detection. Structural parsers still validate the complete payload.
        byte[] content = new byte[8];
        int length;
        try (InputStream input = Files.newInputStream(object.path())) {
            length = input.read(content);
        } catch (IOException e) {
            reject(UploadErrorCode.REJECTED_FORMAT, "media type detection failed");
            return null;
        }
        if (startsWith(content, length, new byte[]{'%', 'P', 'D', 'F', '-'})) {
            return "application/pdf";
        }
        if (startsWith(content, length, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a})) {
            return "image/png";
        }
        if (startsWith(content, length, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return "image/jpeg";
        }
        reject(UploadErrorCode.REJECTED_FORMAT, "media type detection failed");
        return null;
    }

    private boolean startsWith(byte[] content, int length, byte[] signature) {
        if (length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean compatible(String declared, String detected) {
        return declared.equals(detected)
                || ("image/jpeg".equals(declared) && "image/jpeg".equals(detected));
    }

    private static void reject(UploadErrorCode code, String message) {
        throw new UploadSecurityException(code, message);
    }
}
