package org.zipp.ai.ingestion.worker.document;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;
import org.zipp.ai.domain.ingestion.service.EvidenceUnitBuilder;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;
import org.zipp.ai.domain.ingestion.service.VisualCandidateSelectionPolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DocumentProcessingProfilesTest {

    @Test
    void acceptsTheCanonicalProfileAndItsEquivalentLegacyExecutablePathProfile() {
        DocumentProcessingProfile active = DocumentProcessingProfile.of(
                200, "/opt/homebrew/bin/tesseract", "eng+chi_sim", 120, "5.5.2",
                new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03),
                new CanonicalPageAssembler(0.70), new DocumentStructureBuilder(),
                new VisualCandidateSelectionPolicy(12, 0.15, 3),
                new VisualCropDeriver(25_000_000, 10 * 1024 * 1024),
                new EvidenceUnitBuilder(),
                new EvidenceBuildLimits(16L * 1024 * 1024, 5_000_000, 500_000),
                "retrieval-test-v1");

        DocumentProcessingProfiles profiles =
                DocumentProcessingProfiles.withLegacyExecutablePath(active, "/opt/homebrew/bin/tesseract");

        assertEquals(2, profiles.acceptedFingerprints().size());
        assertEquals(active, profiles.require(active.overallFingerprint()));
        DocumentProcessingProfile legacy = profiles.acceptedFingerprints().stream()
                .filter(fingerprint -> !fingerprint.equals(active.overallFingerprint()))
                .findFirst().map(profiles::require).orElseThrow();
        assertNotEquals(active.overallFingerprint(), legacy.overallFingerprint());
        assertEquals(active.parser(), legacy.parser());
        assertEquals(active.retrieval(), legacy.retrieval());
    }

    @Test
    void commandNameDoesNotCreateASecondCompatibilityProfile() {
        DocumentProcessingProfile active = DocumentProcessingProfile.of(
                200, "tesseract", "eng+chi_sim", 120, "5.5.2",
                new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03),
                new CanonicalPageAssembler(0.70), new DocumentStructureBuilder(),
                new VisualCandidateSelectionPolicy(12, 0.15, 3),
                new VisualCropDeriver(25_000_000, 10 * 1024 * 1024),
                new EvidenceUnitBuilder(),
                new EvidenceBuildLimits(16L * 1024 * 1024, 5_000_000, 500_000),
                "retrieval-test-v1");

        DocumentProcessingProfiles profiles =
                DocumentProcessingProfiles.withLegacyExecutablePath(active, "tesseract");

        assertEquals(1, profiles.acceptedFingerprints().size());
    }

    @Test
    void explicitLegacyExecutablePathsAreAcceptedWhenTheCurrentExecutableIsACommandName() {
        DocumentProcessingProfile active = DocumentProcessingProfile.of(
                200, "tesseract", "eng+chi_sim", 120, "5.5.2",
                new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03),
                new CanonicalPageAssembler(0.70), new DocumentStructureBuilder(),
                new VisualCandidateSelectionPolicy(12, 0.15, 3),
                new VisualCropDeriver(25_000_000, 10 * 1024 * 1024),
                new EvidenceUnitBuilder(),
                new EvidenceBuildLimits(16L * 1024 * 1024, 5_000_000, 500_000),
                "retrieval-test-v1");

        DocumentProcessingProfiles profiles = DocumentProcessingProfiles.withLegacyExecutablePaths(
                active, List.of("/usr/local/bin/tesseract", "/opt/homebrew/bin/tesseract"));

        assertEquals(3, profiles.acceptedFingerprints().size());
    }
}
