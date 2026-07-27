package org.zipp.ai.test.trigger.http;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialPageAccessPort;
import org.zipp.ai.domain.material.service.MaterialPreviewService;
import org.zipp.ai.domain.material.service.MaterialRevisionPolicy;
import org.zipp.ai.trigger.http.CurrentOwnerHttpResolver;
import org.zipp.ai.trigger.http.MaterialPreviewController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaterialPreviewControllerTest {
    @Test
    void pageCatalogUsesTheServerOwnerAndReturnsOnlySafePageMetadata() {
        CapturingPages pages = new CapturingPages();
        MaterialPreviewController controller = new MaterialPreviewController(resolver(), service(pages));

        var response = controller.pages("material_1", "version_1", "revision_1");

        assertEquals("0000", response.getCode());
        assertEquals("user_server", pages.owner.ownerKey());
        assertEquals("revision_1", response.getData().revisionId());
        assertEquals(1, response.getData().pages().get(0).pageNo());
    }

    private MaterialPreviewService service(CapturingPages pages) {
        return new MaterialPreviewService(pages,
                artifact -> new MaterialPreviewImage(new byte[]{1}, "image/png", "a".repeat(64)),
                new MaterialRevisionPolicy(), new ProcessingRevisionProfile("a".repeat(64),
                "parser-v1", "cleaner-v1", "chunk-v1", "ocr-v1", "visual-v1"),
                prefix -> prefix + "_1",
                Clock.fixed(Instant.parse("2026-07-20T00:00:00Z"), ZoneOffset.UTC));
    }

    private CurrentOwnerHttpResolver resolver() {
        return new CurrentOwnerHttpResolver() {
            @Override
            public Optional<ResolvedOwner> resolve(String ignoredLegacyOwnerId) {
                return Optional.of(ResolvedOwner.authenticated("user_server"));
            }
        };
    }

    private static final class CapturingPages implements MaterialPageAccessPort {
        private CatalogOwner owner;

        @Override
        public Optional<MaterialPageSet> findPages(CatalogOwner owner, String materialId,
                                                   String versionId, String revisionId) {
            this.owner = owner;
            return Optional.of(new MaterialPageSet(materialId, versionId, revisionId, 1,
                    CatalogProcessingStatus.READY, 100, Set.of(), List.of(new MaterialPageSummary(
                    1, 100, 200, "EXTRACTED", "COMPLETED", 0.9,
                    "COMPLETED", null, true, true))));
        }

        @Override
        public Optional<StoredArtifact> findPreviewArtifact(CatalogOwner owner, String materialId,
                                                            String versionId, String revisionId, int pageNo) {
            return Optional.empty();
        }

        @Override
        public Optional<MaterialReprocessSnapshot> findReprocessSnapshot(CatalogOwner owner,
                                                                         String materialId) {
            return Optional.empty();
        }

        @Override
        public MaterialReprocessResult createOrFindRevision(MaterialReprocessPlan plan) {
            throw new UnsupportedOperationException();
        }
    }
}
