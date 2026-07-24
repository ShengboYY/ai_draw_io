package org.zipp.ai.test.trigger.http;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialDownloadFile;
import org.zipp.ai.domain.material.port.MaterialDownloadPort;
import org.zipp.ai.domain.material.service.MaterialDownloadService;
import org.zipp.ai.trigger.http.CurrentOwnerHttpResolver;
import org.zipp.ai.trigger.http.MaterialDownloadController;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MaterialDownloadControllerTest {
    @Test
    void ownerDownloadsTheExactVersionWithoutReceivingStorageIdentity() {
        CapturingDownloads downloads = new CapturingDownloads(true);
        MaterialDownloadController controller = new MaterialDownloadController(
                resolver("user_1"), new MaterialDownloadService(downloads));

        var response = controller.download("material_1", "version_2");

        assertAll(
                () -> assertEquals(200, response.getStatusCode().value()),
                () -> assertArrayEquals("exact-version".getBytes(StandardCharsets.UTF_8),
                        response.getBody()),
                () -> assertEquals("user_1", downloads.owner.ownerKey()),
                () -> assertEquals("version_2", downloads.versionId),
                () -> assertTrue(response.getHeaders().getContentDisposition()
                        .getFilename().contains("architecture.png")),
                () -> assertFalse(response.getHeaders().toString().contains("object-key")),
                () -> assertFalse(new String(response.getBody(), StandardCharsets.UTF_8)
                        .contains("object-key")));
    }

    @Test
    void nonOwnerCannotDownloadTheVersion() {
        MaterialDownloadController controller = new MaterialDownloadController(
                resolver("user_2"), new MaterialDownloadService(new CapturingDownloads(false)));

        var response = controller.download("material_1", "version_2");

        assertEquals(404, response.getStatusCode().value());
        assertNull(response.getBody());
    }

    private CurrentOwnerHttpResolver resolver(String ownerKey) {
        return new CurrentOwnerHttpResolver() {
            @Override
            public Optional<ResolvedOwner> resolve(String ignoredLegacyOwnerId) {
                return Optional.of(ResolvedOwner.authenticated(ownerKey));
            }
        };
    }

    private static final class CapturingDownloads implements MaterialDownloadPort {
        private final boolean available;
        private CatalogOwner owner;
        private String versionId;

        private CapturingDownloads(boolean available) {
            this.available = available;
        }

        @Override
        public Optional<MaterialDownloadFile> find(CatalogOwner owner, String materialId,
                                                   String versionId) {
            this.owner = owner;
            this.versionId = versionId;
            return available
                    ? Optional.of(new MaterialDownloadFile("architecture.png", "image/png",
                    "exact-version".getBytes(StandardCharsets.UTF_8)))
                    : Optional.empty();
        }
    }
}
