package org.zipp.ai.ingestion.worker.document;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;
import org.zipp.ai.domain.ingestion.service.EvidenceUnitBuilder;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;
import org.zipp.ai.domain.ingestion.service.VisualCandidateSelectionPolicy;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentProcessingProfileTest {

    @Test
    void runtimeParserOcrAndSelectionConfigurationChangesTheFingerprint() {
        var selection = new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03);
        var canonical = new CanonicalPageAssembler(0.70);
        var baseline = DocumentProcessingProfile.of(200, "tesseract", "eng+chi_sim", 120,
                "5.5.0", selection, canonical, new DocumentStructureBuilder(), visual(), cropper(), evidence(),
                limits(), retrieval());

        assertNotEquals(baseline.overallFingerprint(), DocumentProcessingProfile.of(250, "tesseract",
                "eng+chi_sim", 120, "5.5.0", selection, canonical,
                new DocumentStructureBuilder(), visual(), cropper(), evidence(), limits(), retrieval())
                .overallFingerprint());
        assertThrows(IllegalArgumentException.class, () -> DocumentProcessingProfile.of(200, "tesseract",
                "eng", 120, "5.5.0", selection, canonical, new DocumentStructureBuilder(), visual(), cropper(),
                evidence(), limits(), retrieval()));
        assertNotEquals(baseline.overallFingerprint(), DocumentProcessingProfile.of(200, "tesseract",
                "eng+chi_sim", 120, "5.6.0", selection, canonical,
                new DocumentStructureBuilder(), visual(), cropper(), evidence(), limits(), retrieval())
                .overallFingerprint());
        assertNotEquals(baseline.overallFingerprint(), DocumentProcessingProfile.of(200, "tesseract",
                "eng+chi_sim", 120, "5.5.0", selection, canonical,
                new DocumentStructureBuilder(), visual(), new VisualCropDeriver(24_000_000, 10 * 1024 * 1024),
                evidence(), limits(), retrieval())
                .overallFingerprint());
        assertNotEquals(baseline.overallFingerprint(), DocumentProcessingProfile.of(200, "tesseract",
                "eng+chi_sim", 120, "5.5.0", selection, canonical, new DocumentStructureBuilder(), visual(),
                cropper(), evidence(), new EvidenceBuildLimits(8L * 1024 * 1024, 5_000_000, 500_000), retrieval())
                .overallFingerprint());
        assertEquals(new DocumentStructureBuilder().fingerprint(), baseline.structure());
        assertTrue(baseline.visual().contains(visual().fingerprint()));
        assertTrue(baseline.visual().contains(cropper().fingerprint()));
        assertNotEquals(baseline.overallFingerprint(), new DocumentProcessingProfile(baseline.parser(),
                baseline.ocr(), baseline.selection(), baseline.canonical(), "document-structure-v2",
                baseline.visual(), baseline.evidence(), baseline.retrieval())
                .overallFingerprint());
        assertNotEquals(baseline.overallFingerprint(), new DocumentProcessingProfile(baseline.parser(),
                baseline.ocr(), baseline.selection(), baseline.canonical(), baseline.structure(), "visual-v2",
                baseline.evidence(), baseline.retrieval())
                .overallFingerprint());
        assertNotEquals(baseline.overallFingerprint(), new DocumentProcessingProfile(baseline.parser(),
                baseline.ocr(), baseline.selection(), baseline.canonical(), baseline.structure(), baseline.visual(),
                "evidence-v2", baseline.retrieval())
                .overallFingerprint());
        assertNotEquals(baseline.overallFingerprint(), new DocumentProcessingProfile(baseline.parser(),
                baseline.ocr(), baseline.selection(), baseline.canonical(), baseline.structure(), baseline.visual(),
                baseline.evidence(), "retrieval-v2").overallFingerprint());
    }

    @Test
    void revisionAuditProfileIsBoundedAndDerivedFromTheRuntimeConfiguration() {
        var profile = DocumentProcessingProfile.of(200, "tesseract", "eng+chi_sim", 120,
                "5.5.0", new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03),
                new CanonicalPageAssembler(0.70), new DocumentStructureBuilder(), visual(), cropper(), evidence(),
                limits(), retrieval());

        var audit = profile.revisionProfile();

        assertEquals(profile.overallFingerprint(), audit.fingerprint());
        assertTrue(audit.parserVersion().startsWith("pdfbox-3.0.8-"));
        assertTrue(audit.ocrVersion().startsWith("tesseract-5.5.0-"));
        assertTrue(audit.cleanerVersion().startsWith("canonical-"));
        assertTrue(audit.chunkSchemaVersion().startsWith("retrieval-"));
        assertTrue(audit.parserVersion().length() <= 64);
        assertTrue(audit.ocrVersion().length() <= 64);
    }

    private static VisualCandidateSelectionPolicy visual() {
        return new VisualCandidateSelectionPolicy(12, 0.15, 3);
    }

    private static VisualCropDeriver cropper() {
        return new VisualCropDeriver(25_000_000, 10 * 1024 * 1024);
    }

    private static EvidenceUnitBuilder evidence() {
        return new EvidenceUnitBuilder();
    }

    private static EvidenceBuildLimits limits() {
        return new EvidenceBuildLimits(16L * 1024 * 1024, 5_000_000, 500_000);
    }

    private static String retrieval() {
        return "retrieval-test-v1";
    }
}
