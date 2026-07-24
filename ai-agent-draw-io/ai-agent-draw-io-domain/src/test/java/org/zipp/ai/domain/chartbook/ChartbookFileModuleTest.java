package org.zipp.ai.domain.chartbook;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.chartbook.model.aggregate.Chartbook;
import org.zipp.ai.domain.chartbook.model.valobj.*;
import org.zipp.ai.domain.chartbook.port.ChartbookCatalogPort;
import org.zipp.ai.domain.chartbook.service.ChartbookFileModule;
import org.zipp.ai.domain.chartbook.service.DefaultChartbookFileModule;
import org.zipp.ai.domain.material.model.aggregate.Material;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialCatalogPort;
import org.zipp.ai.domain.material.port.MaterialLifecyclePort;
import org.zipp.ai.domain.material.service.MaterialCatalogService;
import org.zipp.ai.domain.material.service.MaterialLifecycleService;
import org.zipp.ai.domain.material.service.MaterialScopePolicy;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ChartbookFileModuleTest {
    private static final CatalogOwner USER = new CatalogOwner(OwnerType.USER, "user_1");
    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    @Test
    void addPromotesConversationFileWithoutCopyingAndIsIdempotent() {
        FakeMaterialStore store = new FakeMaterialStore();
        ChartbookFileModule module = new DefaultChartbookFileModule(
                new FakeChartbooks(), new MaterialCatalogService(store,
                prefix -> prefix + "_catalog", new MaterialScopePolicy()),
                new MaterialLifecycleService(store, prefix -> prefix + "_lifecycle",
                        Clock.fixed(NOW, ZoneOffset.UTC)));
        AddChartbookFileCommand command = new AddChartbookFileCommand(
                USER, "chartbook_1", "material_1", "add-file-1");

        ChartbookFileResult first = module.add(command);
        ChartbookFileResult repeated = module.add(command);

        assertAll(
                () -> assertEquals(RetentionClass.RETAINED,
                        first.file().material().retentionClass()),
                () -> assertEquals(List.of("version_1"), first.file().versions().stream()
                        .map(MaterialVersionSummary::versionId).toList()),
                () -> assertEquals(1, first.file().scopes().stream()
                        .filter(scope -> scope.scopeType() == MaterialScopeType.CHARTBOOK
                                && scope.scopeKey().equals("chartbook_1"))
                        .count()),
                () -> assertEquals(first.file(), repeated.file()),
                () -> assertEquals(1, store.lifecycleApplyCount));
    }

    @Test
    void addRetainedFileAcceptsAConcurrentReplayThatAlreadyCreatedTheScope() {
        FakeMaterialStore store = FakeMaterialStore.retainedWithConcurrentScopeWinner();
        ChartbookFileModule module = new DefaultChartbookFileModule(
                new FakeChartbooks(), new MaterialCatalogService(store,
                prefix -> prefix + "_catalog", new MaterialScopePolicy()),
                new MaterialLifecycleService(store, prefix -> prefix + "_lifecycle",
                        Clock.fixed(NOW, ZoneOffset.UTC)));
        AddChartbookFileCommand command = new AddChartbookFileCommand(
                USER, "chartbook_1", "material_1", "add-file-2");

        ChartbookFileResult result = module.add(command);
        ChartbookFileResult repeated = module.add(command);

        assertAll(
                () -> assertEquals(1, result.file().scopes().stream()
                        .filter(scope -> scope.scopeType() == MaterialScopeType.CHARTBOOK
                                && scope.scopeKey().equals("chartbook_1"))
                        .count()),
                () -> assertEquals(result.file(), repeated.file()),
                () -> assertEquals(1, store.catalogAddCount),
                () -> assertEquals(0, store.lifecycleApplyCount));
    }

    private static final class FakeChartbooks implements ChartbookCatalogPort {
        private final ChartbookView chartbook = new ChartbookView(
                "chartbook_1", USER.ownerKey(), "Architecture", ChartbookStatus.ACTIVE,
                Set.of(), Set.of(), NOW, NOW);

        @Override
        public ChartbookView createOrFind(Chartbook aggregate, String idempotencyKey, Instant createdAt) {
            return chartbook;
        }

        @Override
        public List<ChartbookView> findAll(CatalogOwner owner) {
            return List.of(chartbook);
        }

        @Override
        public Optional<ChartbookView> find(CatalogOwner owner, String chartbookId) {
            return owner.equals(USER) && chartbook.chartbookId().equals(chartbookId)
                    ? Optional.of(chartbook) : Optional.empty();
        }

        @Override public boolean rename(CatalogOwner owner, String chartbookId, String name) { return false; }
        @Override public boolean archive(CatalogOwner owner, String chartbookId) { return false; }
        @Override public boolean assignDiagram(CatalogOwner owner, String diagramId, String chartbookId) { return false; }
        @Override public boolean removeDiagram(CatalogOwner owner, String diagramId) { return false; }
    }

    /** One fake store models the catalog and lifecycle views of the same committed Material row. */
    private static final class FakeMaterialStore implements MaterialCatalogPort, MaterialLifecyclePort {
        private Material material;
        private final List<MaterialVersionSummary> versions = List.of(new MaterialVersionSummary(
                "version_1", 1, "application/pdf", 100, 1,
                CatalogProcessingStatus.READY, 100, NOW.minusSeconds(30)));
        private final List<MaterialScopeReference> scopes = new ArrayList<>();
        private final Map<String, MaterialLifecycleResult> applied = new HashMap<>();
        private final boolean concurrentScopeWinner;
        private int lifecycleApplyCount;
        private int catalogAddCount;

        private FakeMaterialStore() {
            this.concurrentScopeWinner = false;
            this.material = Material.createTemporary("material_1", OwnerType.USER,
                    USER.ownerKey(), MaterialKind.PDF, "Requirements", "conversation_1",
                    NOW.minusSeconds(60));
            scopes.add(new MaterialScopeReference("scope_conversation",
                    MaterialScopeType.CONVERSATION, "conversation_1"));
        }

        private FakeMaterialStore(boolean concurrentScopeWinner) {
            this.concurrentScopeWinner = concurrentScopeWinner;
            MaterialScopeLink library = new MaterialScopeLink(
                    MaterialScopeType.LIBRARY, MaterialScopeType.PERSONAL_LIBRARY_KEY, USER.ownerKey());
            this.material = Material.createRetained("material_1", OwnerType.USER,
                    USER.ownerKey(), MaterialKind.PDF, "Requirements", library, NOW.minusSeconds(60));
            scopes.add(new MaterialScopeReference("scope_library",
                    MaterialScopeType.LIBRARY, MaterialScopeType.PERSONAL_LIBRARY_KEY));
        }

        private static FakeMaterialStore retainedWithConcurrentScopeWinner() {
            return new FakeMaterialStore(true);
        }

        @Override
        public MaterialCatalogPage findMaterials(MaterialCatalogQuery query) {
            return new MaterialCatalogPage(List.of(details().material()), 1, query.limit(), query.offset());
        }

        @Override
        public Optional<MaterialCatalogDetails> findMaterial(CatalogOwner owner, String materialId) {
            return owner.equals(USER) && material.id().equals(materialId)
                    ? Optional.of(details()) : Optional.empty();
        }

        @Override
        public boolean scopeTargetOwned(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey) {
            return owner.equals(USER) && scopeType == MaterialScopeType.CHARTBOOK
                    && "chartbook_1".equals(scopeKey);
        }

        @Override
        public boolean addScope(CatalogOwner owner, String materialId, MaterialScopeReference scope) {
            catalogAddCount++;
            scopes.add(scope);
            return !concurrentScopeWinner;
        }

        @Override
        public boolean removeScope(CatalogOwner owner, String materialId, String linkId) {
            return false;
        }

        @Override
        public Optional<Material> findOwned(CatalogOwner owner, String materialId) {
            return owner.equals(USER) && material.id().equals(materialId)
                    ? Optional.of(material) : Optional.empty();
        }

        @Override
        public Optional<MaterialLifecycleResult> findApplied(CatalogOwner owner, String materialId,
                                                             MaterialLifecycleAction action,
                                                             String requestFingerprint) {
            return Optional.ofNullable(applied.get(action + ":" + requestFingerprint));
        }

        @Override
        public MaterialLifecycleResult apply(MaterialLifecycleMutation mutation) {
            material = mutation.material();
            scopes.add(new MaterialScopeReference(mutation.scopeLink().linkId(),
                    mutation.scopeLink().scopeType(), mutation.scopeLink().scopeKey()));
            MaterialLifecycleResult result = MaterialLifecycleResult.from(material);
            applied.put(mutation.action() + ":" + mutation.requestFingerprint(), result);
            lifecycleApplyCount++;
            return result;
        }

        @Override
        public boolean originConversationAvailable(CatalogOwner owner, String conversationId) {
            return true;
        }

        @Override
        public MaterialDeletionImpact findDeletionImpact(CatalogOwner owner, String materialId) {
            return new MaterialDeletionImpact(materialId, material.lifecycleGeneration(),
                    List.of("version_1"), List.of(), List.of(), List.of(), List.of());
        }

        @Override
        public List<MaterialExpiryCandidate> findExpiredTemporary(Instant now, int limit) {
            return List.of();
        }

        @Override
        public List<MaterialExpiryCandidate> findExpiredTrash(Instant now, int limit) {
            return List.of();
        }

        @Override
        public List<MaterialLifecycleCandidate> findOwnerDeletionCandidates(String ownerKey, int limit) {
            return List.of();
        }

        private MaterialCatalogDetails details() {
            MaterialCatalogItem item = new MaterialCatalogItem(material.id(), material.kind(),
                    material.displayName(), material.retentionClass(), material.lifecycleState(),
                    "version_1", 1, CatalogProcessingStatus.READY, 100, 1, NOW);
            return new MaterialCatalogDetails(item, versions, scopes);
        }
    }
}
