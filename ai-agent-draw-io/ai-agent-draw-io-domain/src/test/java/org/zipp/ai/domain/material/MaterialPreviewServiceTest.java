package org.zipp.ai.domain.material;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;
import org.zipp.ai.domain.ingestion.service.ProcessingRevisionFingerprintPolicy;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialPageAccessPort;
import org.zipp.ai.domain.material.service.MaterialPreviewService;
import org.zipp.ai.domain.material.service.MaterialRevisionPolicy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MaterialPreviewServiceTest {
    private static final CatalogOwner USER = new CatalogOwner(OwnerType.USER, "user_1");
    private static final String HASH = "a".repeat(64);
    private static final String TARGET_PROFILE = "c".repeat(64);

    @Test
    void pageMetadataAndPreviewUseTheOwnerFencedExactArtifact() {
        FakePages pages = new FakePages();
        MaterialPreviewService service = service(pages);

        MaterialPageSet result = service.findPages(USER, "material_1", "version_1", "revision_1");
        MaterialPreviewImage image = service.preview(USER, "material_1", "version_1", "revision_1", 1);

        assertEquals("revision_1", result.revisionId());
        assertEquals("object-version-1", pages.previewArtifact.objectVersionId());
        assertArrayEquals(new byte[]{1, 2, 3}, image.bytes());
    }

    @Test
    void unknownRevisionFailsWithoutAttemptingArtifactRead() {
        FakePages pages = new FakePages();
        pages.pageSet = null;

        CatalogOperationException error = assertThrows(CatalogOperationException.class,
                () -> service(pages).findPages(USER, "material_1", "version_1", "other_revision"));

        assertEquals(CatalogErrorCode.REVISION_NOT_FOUND, error.code());
    }

    @Test
    void excludedPagesMustBeInRangeAndCannotRemoveTheWholeDocument() {
        MaterialPreviewService service = service(new FakePages());

        assertThrows(IllegalArgumentException.class,
                () -> service.replaceExcludedPages(USER, "material_1", Set.of(1, 2, 3), "exclude-1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.replaceExcludedPages(USER, "material_1", Set.of(4), "exclude-2"));
    }

    @Test
    void manualReprocessIsStableForOneIdempotencyKeyAndExclusionSet() {
        FakePages pages = new FakePages();
        MaterialPreviewService service = service(pages);

        service.reprocess(USER, "material_1", "request_1");
        String first = pages.lastPlan.requestFingerprint();
        service.reprocess(USER, "material_1", "request_1");

        assertEquals(first, pages.lastPlan.requestFingerprint());
        assertEquals(Set.of(2), pages.lastPlan.revision().excludedPages());
        assertEquals(TARGET_PROFILE, pages.lastPlan.processingProfile().fingerprint());
        assertEquals(ProcessingRevisionFingerprintPolicy.processingFingerprint(
                TARGET_PROFILE, Set.of(2)), pages.lastPlan.revision().processingFingerprint());
    }

    @Test
    void manualIdempotencyKeySurvivesAnInterveningExclusionChange() {
        FakePages pages = new FakePages();
        MaterialPreviewService service = service(pages);

        service.reprocess(USER, "material_1", "request_1");
        String first = pages.lastPlan.requestFingerprint();
        pages.excludedPages = Set.of(1);
        service.reprocess(USER, "material_1", "request_1");

        assertEquals(first, pages.lastPlan.requestFingerprint());
    }

    private MaterialPreviewService service(FakePages pages) {
        return new MaterialPreviewService(pages,
                artifact -> new MaterialPreviewImage(new byte[]{1, 2, 3}, "image/png", HASH),
                new MaterialRevisionPolicy(), targetProfile(), prefix -> prefix + "_generated",
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC));
    }

    private ProcessingRevisionProfile targetProfile() {
        return new ProcessingRevisionProfile(TARGET_PROFILE, "parser-v2", "cleaner-v2", "chunk-v2",
                "ocr-v2", "visual-v2");
    }

    private static final class FakePages implements MaterialPageAccessPort {
        private MaterialPageSet pageSet = new MaterialPageSet("material_1", "version_1", "revision_1", 1,
                CatalogProcessingStatus.READY, 100, Set.of(), List.of(new MaterialPageSummary(
                1, 100, 200, "EXTRACTED", "COMPLETED", 0.9,
                "COMPLETED", null, true, true)));
        private final StoredArtifact previewArtifact = new StoredArtifact(
                "opaque/page.png", "object-version-1", HASH, 3, "image/png");
        private MaterialReprocessPlan lastPlan;
        private Set<Integer> excludedPages = Set.of(2);

        @Override
        public Optional<MaterialPageSet> findPages(CatalogOwner owner, String materialId,
                                                   String versionId, String revisionId) {
            return Optional.ofNullable(pageSet);
        }

        @Override
        public Optional<StoredArtifact> findPreviewArtifact(CatalogOwner owner, String materialId,
                                                            String versionId, String revisionId, int pageNo) {
            return Optional.of(previewArtifact);
        }

        @Override
        public Optional<MaterialReprocessSnapshot> findReprocessSnapshot(CatalogOwner owner, String materialId) {
            return Optional.of(new MaterialReprocessSnapshot("material_1", "version_1", 3, HASH,
                    "revision_1", 1, "READY",
                    ProcessingRevisionFingerprintPolicy.processingFingerprint(HASH, excludedPages),
                    new ProcessingRevisionProfile(HASH, "parser-v1", "cleaner-v1", "chunk-v1",
                            "ocr-v1", "visual-v1"), excludedPages));
        }

        @Override
        public MaterialReprocessResult createOrFindRevision(MaterialReprocessPlan plan) {
            lastPlan = plan;
            return new MaterialReprocessResult(plan.materialId(), plan.revision().versionId(),
                    plan.revision().id(),
                    2, CatalogProcessingStatus.EXTRACTING, false);
        }
    }
}
