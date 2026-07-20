package org.zipp.ai.test.trigger.http;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialCatalogPort;
import org.zipp.ai.domain.material.service.MaterialCatalogService;
import org.zipp.ai.domain.material.service.MaterialScopePolicy;
import org.zipp.ai.trigger.http.CurrentOwnerHttpResolver;
import org.zipp.ai.trigger.http.MaterialCatalogController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MaterialCatalogControllerTest {
    @Test
    void listUsesOnlyTheServerResolvedOwnerAndReturnsMetadataCards() {
        CurrentOwnerHttpResolver resolver = resolver(ResolvedOwner.authenticated("user_server"));
        MaterialCatalogItem item = new MaterialCatalogItem("material_1", MaterialKind.PDF, "Guide",
                RetentionClass.RETAINED, MaterialLifecycleState.ACTIVE, "version_1", 1,
                CatalogProcessingStatus.READY, 100, 10,
                Instant.parse("2026-07-20T00:00:00Z"));
        CapturingMaterialCatalogPort catalog = new CapturingMaterialCatalogPort(
                new MaterialCatalogPage(List.of(item), 1, 20, 0));
        MaterialCatalogService service = new MaterialCatalogService(catalog, prefix -> prefix + "_1",
                new MaterialScopePolicy());
        MaterialCatalogController controller = new MaterialCatalogController(resolver, service);

        var response = controller.list("Agile", "ACTIVE", 20, 0);

        assertEquals("user_server", catalog.query.owner().ownerKey());
        assertEquals("0000", response.getCode());
        assertEquals("material_1", response.getData().items().get(0).materialId());
    }

    @Test
    void missingCredentialReturnsStableCatalogError() {
        CurrentOwnerHttpResolver resolver = resolver(null);
        MaterialCatalogController controller = new MaterialCatalogController(
                resolver, new MaterialCatalogService(new CapturingMaterialCatalogPort(
                        new MaterialCatalogPage(List.of(), 0, 20, 0)), prefix -> prefix + "_1",
                        new MaterialScopePolicy()));

        var response = controller.list(null, null, 20, 0);

        assertEquals("REGISTERED_USER_REQUIRED", response.getCode());
        assertNull(response.getData());
    }

    private CurrentOwnerHttpResolver resolver(ResolvedOwner owner) {
        // A small override keeps this transport test independent of JVM agent attachment.
        return new CurrentOwnerHttpResolver() {
            @Override
            public Optional<ResolvedOwner> resolve(String ignoredLegacyOwnerId) {
                return Optional.ofNullable(owner);
            }
        };
    }

    private static final class CapturingMaterialCatalogPort implements MaterialCatalogPort {
        private final MaterialCatalogPage page;
        private MaterialCatalogQuery query;

        private CapturingMaterialCatalogPort(MaterialCatalogPage page) {
            this.page = page;
        }

        @Override
        public MaterialCatalogPage findMaterials(MaterialCatalogQuery query) {
            this.query = query;
            return page;
        }

        @Override
        public Optional<MaterialCatalogDetails> findMaterial(CatalogOwner owner, String materialId) {
            return Optional.empty();
        }

        @Override
        public boolean scopeTargetOwned(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey) {
            return false;
        }

        @Override
        public boolean addScope(CatalogOwner owner, String materialId, MaterialScopeReference scope) {
            return false;
        }

        @Override
        public boolean removeScope(CatalogOwner owner, String materialId, String linkId) {
            return false;
        }
    }
}
