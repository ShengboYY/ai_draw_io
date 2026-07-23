package org.zipp.ai.domain.material;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialCatalogPort;
import org.zipp.ai.domain.material.service.MaterialCatalogService;
import org.zipp.ai.domain.material.service.MaterialScopePolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MaterialCatalogServiceTest {
    private static final CatalogOwner USER = new CatalogOwner(OwnerType.USER, "user_1");

    @Test
    void anonymousOwnerCannotBrowseLongLivedCatalog() {
        MaterialCatalogService service = service(new FakeCatalog(details(scopes("scope_1"))));

        CatalogOperationException error = assertThrows(CatalogOperationException.class,
                () -> service.findMaterials(new MaterialCatalogQuery(
                        new CatalogOwner(OwnerType.ANONYMOUS, "anon_1"), null, null, 20, 0)));

        assertEquals(CatalogErrorCode.REGISTERED_USER_REQUIRED, error.code());
    }

    @Test
    void scopedBrowseRequiresAnOwnedDiagramAndReturnsOnlyThatScope() {
        FakeCatalog catalog = new FakeCatalog(details(scopes("scope_1")));
        MaterialCatalogService service = service(catalog);

        MaterialCatalogPage page = service.findMaterialsForScope(new MaterialScopeCatalogQuery(
                USER, MaterialScopeType.DIAGRAM, "diagram_1", MaterialLifecycleState.ACTIVE, 20, 0));

        assertEquals("diagram_1", catalog.lastScopeKey);
        assertEquals(1, page.items().size());

        catalog.targetOwned = false;
        CatalogOperationException error = assertThrows(CatalogOperationException.class,
                () -> service.findMaterialsForScope(new MaterialScopeCatalogQuery(
                        USER, MaterialScopeType.DIAGRAM, "other_diagram", MaterialLifecycleState.ACTIVE, 20, 0)));
        assertEquals(CatalogErrorCode.SCOPE_TARGET_NOT_FOUND, error.code());
    }

    @Test
    void scopeAdditionRequiresAnOwnedDurableTarget() {
        FakeCatalog catalog = new FakeCatalog(details(scopes("scope_1")));
        catalog.targetOwned = false;
        MaterialCatalogService service = service(catalog);

        CatalogOperationException error = assertThrows(CatalogOperationException.class,
                () -> service.addScope(new MaterialScopeCommand(
                        USER, "material_1", MaterialScopeType.CHARTBOOK, "other_book")));

        assertEquals(CatalogErrorCode.SCOPE_TARGET_NOT_FOUND, error.code());
        assertEquals(1, catalog.details.scopes().size());
    }

    @Test
    void finalRetainedScopeCannotBeSilentlyRemoved() {
        MaterialCatalogService service = service(new FakeCatalog(details(scopes("scope_1"))));

        CatalogOperationException error = assertThrows(CatalogOperationException.class,
                () -> service.removeScope(USER, "material_1", "scope_1"));

        assertEquals(CatalogErrorCode.LAST_RETAINED_SCOPE, error.code());
    }

    @Test
    void addingAndRemovingASecondaryScopeOnlyChangesAssociations() {
        FakeCatalog catalog = new FakeCatalog(details(scopes("scope_1")));
        MaterialCatalogService service = service(catalog);

        MaterialCatalogDetails added = service.addScope(new MaterialScopeCommand(
                USER, "material_1", MaterialScopeType.CHARTBOOK, "book_1"));
        String addedLink = added.scopes().stream()
                .filter(scope -> scope.scopeType() == MaterialScopeType.CHARTBOOK)
                .findFirst().orElseThrow().linkId();
        MaterialCatalogDetails removed = service.removeScope(USER, "material_1", addedLink);

        assertEquals(2, added.scopes().size());
        assertEquals(1, removed.scopes().size());
        assertEquals("material_1", removed.material().materialId());
    }

    @Test
    void personalLibraryAliasesAreCanonicalizedAndFailedWritesAreNotReportedAsSuccess() {
        FakeCatalog catalog = new FakeCatalog(details(scopes("scope_1")));
        MaterialCatalogService service = service(catalog);

        service.addScope(new MaterialScopeCommand(
                USER, "material_1", MaterialScopeType.LIBRARY, "user_1"));
        assertEquals(MaterialScopeType.PERSONAL_LIBRARY_KEY, catalog.lastScopeKey);

        catalog.addSucceeds = false;
        CatalogOperationException error = assertThrows(CatalogOperationException.class,
                () -> service.addScope(new MaterialScopeCommand(
                        USER, "material_1", MaterialScopeType.DIAGRAM, "diagram_1")));
        assertEquals(CatalogErrorCode.CATALOG_CONFLICT, error.code());
    }

    private MaterialCatalogService service(FakeCatalog catalog) {
        return new MaterialCatalogService(catalog, prefix -> prefix + "_generated", new MaterialScopePolicy());
    }

    private static MaterialCatalogDetails details(List<MaterialScopeReference> scopes) {
        MaterialCatalogItem item = new MaterialCatalogItem("material_1", MaterialKind.PDF, "Guide",
                RetentionClass.RETAINED, MaterialLifecycleState.ACTIVE, "version_1", 1,
                CatalogProcessingStatus.READY, 100, 10, Instant.parse("2026-07-20T00:00:00Z"));
        return new MaterialCatalogDetails(item, List.of(new MaterialVersionSummary(
                "version_1", 1, "application/pdf", 100, 10,
                CatalogProcessingStatus.READY, 100, Instant.parse("2026-07-20T00:00:00Z"))), scopes);
    }

    private static List<MaterialScopeReference> scopes(String id) {
        return List.of(new MaterialScopeReference(id, MaterialScopeType.LIBRARY, "user_1"));
    }

    private static final class FakeCatalog implements MaterialCatalogPort {
        private MaterialCatalogDetails details;
        private boolean targetOwned = true;
        private boolean addSucceeds = true;
        private String lastScopeKey;

        private FakeCatalog(MaterialCatalogDetails details) {
            this.details = details;
        }

        @Override
        public MaterialCatalogPage findMaterials(MaterialCatalogQuery query) {
            return new MaterialCatalogPage(List.of(details.material()), 1, query.limit(), query.offset());
        }

        @Override
        public MaterialCatalogPage findMaterialsForScope(MaterialScopeCatalogQuery query) {
            return new MaterialCatalogPage(List.of(details.material()), 1, query.limit(), query.offset());
        }

        @Override
        public Optional<MaterialCatalogDetails> findMaterial(CatalogOwner owner, String materialId) {
            return "user_1".equals(owner.ownerKey()) && details.material().materialId().equals(materialId)
                    ? Optional.of(details) : Optional.empty();
        }

        @Override
        public boolean scopeTargetOwned(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey) {
            lastScopeKey = scopeKey;
            return targetOwned;
        }

        @Override
        public boolean addScope(CatalogOwner owner, String materialId, MaterialScopeReference scope) {
            if (!addSucceeds) return false;
            List<MaterialScopeReference> updated = new ArrayList<>(details.scopes());
            if (updated.stream().noneMatch(existing -> existing.scopeType() == scope.scopeType()
                    && existing.scopeKey().equals(scope.scopeKey()))) updated.add(scope);
            details = new MaterialCatalogDetails(details.material(), details.versions(), updated);
            return true;
        }

        @Override
        public boolean removeScope(CatalogOwner owner, String materialId, String linkId) {
            List<MaterialScopeReference> updated = details.scopes().stream()
                    .filter(scope -> !scope.linkId().equals(linkId)).toList();
            if (updated.size() == details.scopes().size()) return false;
            details = new MaterialCatalogDetails(details.material(), details.versions(), updated);
            return true;
        }
    }
}
