package org.zipp.ai.ingestion.worker.document;

import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;
import org.zipp.ai.domain.ingestion.service.EvidenceUnitBuilder;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;
import org.zipp.ai.domain.ingestion.service.NativeBlockConfidencePolicy;
import org.zipp.ai.domain.ingestion.service.ProcessingStageFingerprintPolicy;
import org.zipp.ai.domain.ingestion.service.VisualCandidateSelectionPolicy;
import org.zipp.ai.domain.ingestion.service.TextBlockKindPolicy;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Immutable processing configuration used in revision and stage fingerprints. */
public record DocumentProcessingProfile(String parser, String ocr, String selection,
                                        String canonical, String structure, String visual, String evidence) {

    public DocumentProcessingProfile {
        parser = requireText(parser, "parser");
        ocr = requireText(ocr, "ocr");
        selection = requireText(selection, "selection");
        canonical = requireText(canonical, "canonical");
        structure = requireText(structure, "structure");
        visual = requireText(visual, "visual");
        evidence = requireText(evidence, "evidence");
    }

    public static DocumentProcessingProfile of(int renderDpi, String executable, String languages,
                                               long timeoutSeconds, String tesseractRuntimeVersion,
                                               OcrSelectionPolicy selection,
                                               CanonicalPageAssembler canonical,
                                               DocumentStructureBuilder structure,
                                               VisualCandidateSelectionPolicy visual,
                                               VisualCropDeriver cropper,
                                               EvidenceUnitBuilder evidenceBuilder,
                                               EvidenceBuildLimits evidenceLimits) {
        String normalizedLanguages = requireText(languages, "languages");
        if (!"eng+chi_sim".equals(normalizedLanguages)) {
            throw new IllegalArgumentException("WP3B calibration requires Tesseract languages eng+chi_sim");
        }
        return new DocumentProcessingProfile(
                "pdfbox-3.0.8:render-dpi=" + renderDpi + ":rotation-v2:image-normalize-v2:"
                        + new NativeBlockConfidencePolicy().fingerprint() + ":"
                        + new TextBlockKindPolicy().fingerprint(),
                "tesseract:" + requireText(tesseractRuntimeVersion, "tesseractRuntimeVersion")
                        + ":executable=" + requireText(executable, "executable")
                        + ":languages=" + normalizedLanguages
                        + ":timeout=" + timeoutSeconds,
                selection.fingerprint(), canonical.fingerprint(), structure.fingerprint(),
                visual.fingerprint() + ":" + cropper.fingerprint(),
                evidenceBuilder.fingerprint() + ":" + evidenceLimits.fingerprint());
    }

    public String overallFingerprint() {
        return sha256(parser + ":" + ocr + ":" + selection + ":" + canonical
                + ":" + structure + ":" + visual + ":" + evidence + ":chunk-v1");
    }

    public ProcessingRevisionProfile revisionProfile() {
        return new ProcessingRevisionProfile(overallFingerprint(),
                auditVersion("pdfbox-3.0.8", parser),
                auditVersion("canonical", canonical),
                "chunk-v1",
                auditVersion(ocrAuditPrefix(), ocr),
                auditVersion("visual", visual));
    }

    public String extractionInput(String sourceSha256) {
        return ProcessingStageFingerprintPolicy.extractionInput(sourceSha256, overallFingerprint());
    }

    public static String pinnedExtractionInput(String sourceSha256, String processingFingerprint) {
        return ProcessingStageFingerprintPolicy.extractionInput(sourceSha256, processingFingerprint);
    }

    public String ocrInput(String pageImageSha256, String nativeExtractionSha256) {
        return sha256(pageImageSha256 + ":" + nativeExtractionSha256 + ":" + ocr + ":" + selection);
    }

    public String canonicalInput(String rawExtractionSha256) {
        return sha256(rawExtractionSha256 + ":" + canonical);
    }

    public String structureSeed() {
        return ProcessingStageFingerprintPolicy.structureSeed(overallFingerprint());
    }

    public String structureInput(List<String> orderedCanonicalHashes) {
        return ProcessingStageFingerprintPolicy.structureInput(orderedCanonicalHashes, overallFingerprint());
    }

    public String visualInput(String structureHash, String structureArtifactHash) {
        return ProcessingStageFingerprintPolicy.visualInput(
                structureHash, structureArtifactHash, overallFingerprint());
    }

    public String evidenceInput(String visualManifestHash) {
        return ProcessingStageFingerprintPolicy.evidenceInput(visualManifestHash, overallFingerprint());
    }

    public String retrievalInput(String evidenceManifestHash) {
        return ProcessingStageFingerprintPolicy.retrievalInput(evidenceManifestHash, overallFingerprint());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private String ocrRuntimeVersion() {
        String marker = "tesseract:";
        int start = ocr.indexOf(marker);
        int end = ocr.indexOf(":executable=", start + marker.length());
        return start < 0 || end < 0 ? "unknown" : ocr.substring(start + marker.length(), end);
    }

    private String ocrAuditPrefix() {
        String runtimeVersion = ocrRuntimeVersion();
        return runtimeVersion.toLowerCase(java.util.Locale.ROOT).startsWith("tesseract")
                ? runtimeVersion : "tesseract-" + runtimeVersion;
    }

    private static String auditVersion(String prefix, String configuration) {
        String normalizedPrefix = prefix.replaceAll("[^A-Za-z0-9._-]", "-");
        String suffix = sha256(configuration).substring(0, 12);
        int maxPrefixLength = 64 - suffix.length() - 1;
        if (normalizedPrefix.length() > maxPrefixLength) {
            normalizedPrefix = normalizedPrefix.substring(0, maxPrefixLength);
        }
        return normalizedPrefix + "-" + suffix;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
