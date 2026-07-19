package org.zipp.ai.ingestion.worker.document;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;

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
                "5.5.0", selection, canonical, new DocumentStructureBuilder());

        assertNotEquals(baseline.overallFingerprint(), DocumentProcessingProfile.of(250, "tesseract",
                "eng+chi_sim", 120, "5.5.0", selection, canonical,
                new DocumentStructureBuilder()).overallFingerprint());
        assertThrows(IllegalArgumentException.class, () -> DocumentProcessingProfile.of(200, "tesseract",
                "eng", 120, "5.5.0", selection, canonical, new DocumentStructureBuilder()));
        assertNotEquals(baseline.overallFingerprint(), DocumentProcessingProfile.of(200, "tesseract",
                "eng+chi_sim", 120, "5.6.0", selection, canonical,
                new DocumentStructureBuilder()).overallFingerprint());
        assertEquals(new DocumentStructureBuilder().fingerprint(), baseline.structure());
        assertNotEquals(baseline.overallFingerprint(), new DocumentProcessingProfile(baseline.parser(),
                baseline.ocr(), baseline.selection(), baseline.canonical(), "document-structure-v2")
                .overallFingerprint());
    }

    @Test
    void revisionAuditProfileIsBoundedAndDerivedFromTheRuntimeConfiguration() {
        var profile = DocumentProcessingProfile.of(200, "tesseract", "eng+chi_sim", 120,
                "5.5.0", new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03),
                new CanonicalPageAssembler(0.70), new DocumentStructureBuilder());

        var audit = profile.revisionProfile();

        assertEquals(profile.overallFingerprint(), audit.fingerprint());
        assertTrue(audit.parserVersion().startsWith("pdfbox-3.0.8-"));
        assertTrue(audit.ocrVersion().startsWith("tesseract-5.5.0-"));
        assertTrue(audit.cleanerVersion().startsWith("canonical-"));
        assertTrue(audit.parserVersion().length() <= 64);
        assertTrue(audit.ocrVersion().length() <= 64);
    }
}
