package org.zipp.ai.domain.chartbook;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.chartbook.model.aggregate.Chartbook;
import org.zipp.ai.domain.chartbook.model.valobj.*;
import org.zipp.ai.domain.chartbook.port.ChartbookCatalogPort;
import org.zipp.ai.domain.chartbook.service.ChartbookCatalogService;
import org.zipp.ai.domain.chartbook.service.ChartbookFileModule;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialCatalogPort;
import org.zipp.ai.domain.material.service.MaterialCatalogService;
import org.zipp.ai.domain.material.service.MaterialScopePolicy;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ChartbookCatalogServiceTest {
    private static final CatalogOwner USER = new CatalogOwner(OwnerType.USER, "user_1");
    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void createIsIdempotentAndAnonymousCreationIsRejected() {
        FakeChartbooks repository = new FakeChartbooks();
        ChartbookCatalogService service = service(repository, new FakeMaterials());

        ChartbookView first = service.create(new CreateChartbookCommand(USER, "request_1", "Agile"));
        ChartbookView repeated = service.create(new CreateChartbookCommand(USER, "request_1", "Ignored"));

        assertEquals(first.chartbookId(), repeated.chartbookId());
        CatalogOperationException error = assertThrows(CatalogOperationException.class,
                () -> service.create(new CreateChartbookCommand(
                        new CatalogOwner(OwnerType.ANONYMOUS, "anon"), "request_2", "No")));
        assertEquals(CatalogErrorCode.REGISTERED_USER_REQUIRED, error.code());
    }

    @Test
    void legacyMaterialAssociationDelegatesToTheUnifiedFileModule() {
        FakeChartbooks repository = new FakeChartbooks();
        FakeMaterials materials = new FakeMaterials();
        CapturingFiles files = new CapturingFiles();
        ChartbookCatalogService service = service(repository, materials, files);
        ChartbookView book = service.create(new CreateChartbookCommand(USER, "request_1", "Agile"));
        files.result = new ChartbookFileResult(book, materials.details);

        service.addMaterial(USER, book.chartbookId(), "material_1");
        ChartbookView withDiagram = service.assignDiagram(USER, "diagram_1", book.chartbookId());

        assertTrue(withDiagram.diagramIds().contains("diagram_1"));
        assertEquals(USER, files.command.owner());
        assertEquals(book.chartbookId(), files.command.chartbookId());
        assertEquals("material_1", files.command.materialId());
        assertEquals("legacy-add:cb_generated:material_1", files.command.idempotencyKey());
    }

    @Test
    void archivedChartbookRejectsFurtherMutations() {
        FakeChartbooks repository = new FakeChartbooks();
        ChartbookCatalogService service = service(repository, new FakeMaterials());
        ChartbookView book = service.create(new CreateChartbookCommand(USER, "request_1", "Agile"));

        service.archive(USER, book.chartbookId());
        CatalogOperationException error = assertThrows(CatalogOperationException.class,
                () -> service.rename(USER, book.chartbookId(), "New name"));

        assertEquals(CatalogErrorCode.CHARTBOOK_ARCHIVED, error.code());
    }

    private ChartbookCatalogService service(FakeChartbooks chartbooks, FakeMaterials materials) {
        return service(chartbooks, materials,
                command -> { throw new AssertionError("unexpected add file"); });
    }

    private ChartbookCatalogService service(FakeChartbooks chartbooks, FakeMaterials materials,
                                            ChartbookFileModule files) {
        var materialService = new MaterialCatalogService(
                materials, prefix -> prefix + "_generated", new MaterialScopePolicy());
        return new ChartbookCatalogService(chartbooks, materialService, files,
                prefix -> prefix + "_generated", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class CapturingFiles implements ChartbookFileModule {
        private AddChartbookFileCommand command;
        private ChartbookFileResult result;

        @Override
        public ChartbookFileResult add(AddChartbookFileCommand command) {
            this.command = command;
            return result;
        }
    }

    private static final class FakeChartbooks implements ChartbookCatalogPort {
        private final Map<String, ChartbookView> values = new LinkedHashMap<>();
        private final Map<String, String> requests = new HashMap<>();

        @Override
        public ChartbookView createOrFind(Chartbook chartbook, String key, Instant createdAt) {
            String existing = requests.get(key);
            if (existing != null) return values.get(existing);
            ChartbookView view = new ChartbookView(chartbook.id(), chartbook.ownerKey(), chartbook.name(),
                    chartbook.status(), Set.of(), Set.of(), createdAt, createdAt);
            requests.put(key, view.chartbookId());
            values.put(view.chartbookId(), view);
            return view;
        }

        @Override
        public List<ChartbookView> findAll(CatalogOwner owner) {
            return values.values().stream().filter(value -> value.ownerKey().equals(owner.ownerKey())).toList();
        }

        @Override
        public Optional<ChartbookView> find(CatalogOwner owner, String chartbookId) {
            return Optional.ofNullable(values.get(chartbookId))
                    .filter(value -> value.ownerKey().equals(owner.ownerKey()));
        }

        @Override
        public boolean rename(CatalogOwner owner, String chartbookId, String name) {
            ChartbookView current = find(owner, chartbookId).orElse(null);
            if (current == null || current.status() != ChartbookStatus.ACTIVE) return false;
            values.put(chartbookId, new ChartbookView(chartbookId, owner.ownerKey(), name, current.status(),
                    current.diagramIds(), current.materialIds(), current.createdAt(), NOW));
            return true;
        }

        @Override
        public boolean archive(CatalogOwner owner, String chartbookId) {
            ChartbookView current = find(owner, chartbookId).orElse(null);
            if (current == null || current.status() != ChartbookStatus.ACTIVE) return false;
            values.put(chartbookId, new ChartbookView(chartbookId, owner.ownerKey(), current.name(),
                    ChartbookStatus.ARCHIVED, current.diagramIds(), current.materialIds(),
                    current.createdAt(), NOW));
            return true;
        }

        @Override
        public boolean assignDiagram(CatalogOwner owner, String diagramId, String chartbookId) {
            ChartbookView current = find(owner, chartbookId).orElse(null);
            if (current == null || !"diagram_1".equals(diagramId)) return false;
            Set<String> diagrams = new LinkedHashSet<>(current.diagramIds());
            diagrams.add(diagramId);
            values.put(chartbookId, new ChartbookView(chartbookId, owner.ownerKey(), current.name(),
                    current.status(), diagrams, current.materialIds(), current.createdAt(), NOW));
            return true;
        }

        @Override
        public boolean removeDiagram(CatalogOwner owner, String diagramId) {
            return "diagram_1".equals(diagramId);
        }
    }

    private static final class FakeMaterials implements MaterialCatalogPort {
        private MaterialCatalogDetails details = new MaterialCatalogDetails(
                new MaterialCatalogItem("material_1", MaterialKind.PDF, "Guide", RetentionClass.RETAINED,
                        MaterialLifecycleState.ACTIVE, "version_1", 1,
                        CatalogProcessingStatus.READY, 100, 10, NOW),
                List.of(), new ArrayList<>(List.of(
                        new MaterialScopeReference("scope_1", MaterialScopeType.LIBRARY, "user_1"))));

        @Override
        public MaterialCatalogPage findMaterials(MaterialCatalogQuery query) {
            return new MaterialCatalogPage(List.of(details.material()), 1, query.limit(), query.offset());
        }

        @Override
        public Optional<MaterialCatalogDetails> findMaterial(CatalogOwner owner, String materialId) {
            return owner.equals(USER) && "material_1".equals(materialId) ? Optional.of(details) : Optional.empty();
        }

        @Override
        public boolean scopeTargetOwned(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey) {
            return owner.equals(USER);
        }

        @Override
        public boolean addScope(CatalogOwner owner, String materialId, MaterialScopeReference scope) {
            List<MaterialScopeReference> updated = new ArrayList<>(details.scopes());
            updated.add(scope);
            details = new MaterialCatalogDetails(details.material(), details.versions(), updated);
            return true;
        }

        @Override
        public boolean removeScope(CatalogOwner owner, String materialId, String linkId) {
            return false;
        }
    }
}
