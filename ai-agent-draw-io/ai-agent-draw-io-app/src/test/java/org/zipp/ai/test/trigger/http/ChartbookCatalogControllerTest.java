package org.zipp.ai.test.trigger.http;

import org.junit.jupiter.api.Test;
import org.zipp.ai.api.dto.ChartbookRequestDTO;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.chartbook.model.aggregate.Chartbook;
import org.zipp.ai.domain.chartbook.model.valobj.*;
import org.zipp.ai.domain.chartbook.port.ChartbookCatalogPort;
import org.zipp.ai.domain.chartbook.service.ChartbookCatalogService;
import org.zipp.ai.domain.chartbook.service.ChartbookFileModule;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.MaterialCatalogPort;
import org.zipp.ai.domain.material.service.MaterialCatalogService;
import org.zipp.ai.domain.material.service.MaterialScopePolicy;
import org.zipp.ai.trigger.http.ChartbookCatalogController;
import org.zipp.ai.trigger.http.CurrentOwnerHttpResolver;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChartbookCatalogControllerTest {
    @Test
    void createCarriesServerOwnerAndIdempotencyKeyIntoTheDomainCommand() {
        CurrentOwnerHttpResolver resolver = new CurrentOwnerHttpResolver() {
            @Override
            public Optional<ResolvedOwner> resolve(String ignoredLegacyOwnerId) {
                return Optional.of(ResolvedOwner.authenticated("user_server"));
            }
        };
        CapturingChartbookCatalogPort chartbooks = new CapturingChartbookCatalogPort();
        MaterialCatalogService materials = new MaterialCatalogService(new EmptyMaterialCatalogPort(),
                prefix -> prefix + "_1", new MaterialScopePolicy());
        ChartbookFileModule files = command -> { throw new AssertionError("unexpected add file"); };
        ChartbookCatalogService service = new ChartbookCatalogService(chartbooks, materials, files,
                prefix -> "book_1", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        ChartbookCatalogController controller = new ChartbookCatalogController(resolver, service, files);

        var response = controller.create("request_1", new ChartbookRequestDTO("Agile"));

        assertEquals("user_server", chartbooks.created.ownerKey());
        assertEquals("request_1", chartbooks.idempotencyKey);
        assertEquals("0000", response.getCode());
        assertEquals("book_1", response.getData().chartbookId());
    }

    @Test
    void addFileUsesServerOwnerAndReturnsTheFinalMaterialView() {
        CurrentOwnerHttpResolver resolver = new CurrentOwnerHttpResolver() {
            @Override
            public Optional<ResolvedOwner> resolve(String ignoredLegacyOwnerId) {
                return Optional.of(ResolvedOwner.authenticated("user_server"));
            }
        };
        CapturingChartbookFileModule files = new CapturingChartbookFileModule();
        MaterialCatalogService materials = new MaterialCatalogService(new EmptyMaterialCatalogPort(),
                prefix -> prefix + "_1", new MaterialScopePolicy());
        ChartbookCatalogService service = new ChartbookCatalogService(
                new CapturingChartbookCatalogPort(), materials, files,
                prefix -> "book_1", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        ChartbookCatalogController controller = new ChartbookCatalogController(resolver, service, files);

        var response = controller.addFile("chartbook_1", "material_1", "add-file-1");

        assertEquals("user_server", files.command.owner().ownerKey());
        assertEquals("chartbook_1", files.command.chartbookId());
        assertEquals("material_1", files.command.materialId());
        assertEquals("add-file-1", files.command.idempotencyKey());
        assertEquals("RETAINED", response.getData().material().retentionClass());
        assertEquals("CHARTBOOK", response.getData().scopes().get(0).scopeType());
    }

    @Test
    void diagramChartbookReturnsEmptyDataWhenNoChartbookIsAssigned() {
        CurrentOwnerHttpResolver resolver = new CurrentOwnerHttpResolver() {
            @Override
            public Optional<ResolvedOwner> resolve(String ignoredLegacyOwnerId) {
                return Optional.of(ResolvedOwner.authenticated("user_server"));
            }
        };
        ChartbookFileModule files = command -> {
            throw new AssertionError("unexpected add file");
        };
        MaterialCatalogService materials = new MaterialCatalogService(new EmptyMaterialCatalogPort(),
                prefix -> prefix + "_1", new MaterialScopePolicy());
        ChartbookCatalogService service = new ChartbookCatalogService(
                new CapturingChartbookCatalogPort(), materials, files,
                prefix -> "book_1", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        ChartbookCatalogController controller = new ChartbookCatalogController(resolver, service, files);

        var response = controller.diagramChartbook("diagram_without_book");

        assertEquals("0000", response.getCode());
        assertNull(response.getData());
    }

    @Test
    void removeFileUsesTheIdempotentUnifiedModuleOperation() {
        CurrentOwnerHttpResolver resolver = new CurrentOwnerHttpResolver() {
            @Override
            public Optional<ResolvedOwner> resolve(String ignoredLegacyOwnerId) {
                return Optional.of(ResolvedOwner.authenticated("user_server"));
            }
        };
        CapturingChartbookFileModule files = new CapturingChartbookFileModule();
        MaterialCatalogService materials = new MaterialCatalogService(new EmptyMaterialCatalogPort(),
                prefix -> prefix + "_1", new MaterialScopePolicy());
        ChartbookCatalogService service = new ChartbookCatalogService(
                new CapturingChartbookCatalogPort(), materials, files,
                prefix -> "book_1", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        ChartbookCatalogController controller = new ChartbookCatalogController(resolver, service, files);

        var response = controller.removeFile("chartbook_1", "material_1", "remove-file-1");

        assertEquals("user_server", files.removeCommand.owner().ownerKey());
        assertEquals("remove-file-1", files.removeCommand.idempotencyKey());
        assertEquals("CONVERSATION", response.getData().scopes().get(0).scopeType());
    }

    private static final class CapturingChartbookFileModule implements ChartbookFileModule {
        private AddChartbookFileCommand command;
        private RemoveChartbookFileCommand removeCommand;

        @Override
        public ChartbookFileResult add(AddChartbookFileCommand command) {
            this.command = command;
            ChartbookView chartbook = new ChartbookView(command.chartbookId(),
                    command.owner().ownerKey(), "Architecture", ChartbookStatus.ACTIVE,
                    Set.of(), Set.of(command.materialId()), Instant.EPOCH, Instant.EPOCH);
            MaterialCatalogItem item = new MaterialCatalogItem(command.materialId(), MaterialKind.PDF,
                    "Requirements", RetentionClass.RETAINED, MaterialLifecycleState.ACTIVE,
                    "version_1", 1, CatalogProcessingStatus.READY, 100,
                    CatalogSearchStatus.SEARCHABLE, 1, Instant.EPOCH);
            MaterialCatalogDetails file = new MaterialCatalogDetails(item, List.of(),
                    List.of(new MaterialScopeReference("scope_1", MaterialScopeType.CHARTBOOK,
                            command.chartbookId())));
            return new ChartbookFileResult(chartbook, file);
        }

        @Override
        public ChartbookFileResult remove(RemoveChartbookFileCommand command) {
            this.removeCommand = command;
            ChartbookView chartbook = new ChartbookView(command.chartbookId(),
                    command.owner().ownerKey(), "Architecture", ChartbookStatus.ACTIVE,
                    Set.of(), Set.of(), Instant.EPOCH, Instant.EPOCH);
            MaterialCatalogItem item = new MaterialCatalogItem(command.materialId(), MaterialKind.PDF,
                    "Requirements", RetentionClass.TEMPORARY, MaterialLifecycleState.ACTIVE,
                    "version_1", 1, CatalogProcessingStatus.READY, 100,
                    CatalogSearchStatus.NOT_APPLICABLE, 1, Instant.EPOCH);
            MaterialCatalogDetails file = new MaterialCatalogDetails(item, List.of(),
                    List.of(new MaterialScopeReference("scope_conversation",
                            MaterialScopeType.CONVERSATION, "conversation_1")));
            return new ChartbookFileResult(chartbook, file);
        }
    }

    private static final class CapturingChartbookCatalogPort implements ChartbookCatalogPort {
        private Chartbook created;
        private String idempotencyKey;

        @Override
        public ChartbookView createOrFind(Chartbook chartbook, String idempotencyKey, Instant createdAt) {
            this.created = chartbook;
            this.idempotencyKey = idempotencyKey;
            return new ChartbookView(chartbook.id(), chartbook.ownerKey(), chartbook.name(),
                    chartbook.status(), Set.of(), Set.of(), createdAt, createdAt);
        }

        @Override public List<ChartbookView> findAll(CatalogOwner owner) { return List.of(); }
        @Override public Optional<ChartbookView> find(CatalogOwner owner, String chartbookId) { return Optional.empty(); }
        @Override public boolean rename(CatalogOwner owner, String chartbookId, String name) { return false; }
        @Override public boolean archive(CatalogOwner owner, String chartbookId) { return false; }
        @Override public boolean assignDiagram(CatalogOwner owner, String diagramId, String chartbookId) { return false; }
        @Override public boolean removeDiagram(CatalogOwner owner, String diagramId) { return false; }
    }

    private static final class EmptyMaterialCatalogPort implements MaterialCatalogPort {
        @Override public MaterialCatalogPage findMaterials(MaterialCatalogQuery query) {
            return new MaterialCatalogPage(List.of(), 0, query.limit(), query.offset());
        }
        @Override public Optional<MaterialCatalogDetails> findMaterial(CatalogOwner owner, String materialId) { return Optional.empty(); }
        @Override public boolean scopeTargetOwned(CatalogOwner owner, MaterialScopeType scopeType, String scopeKey) { return false; }
        @Override public boolean addScope(CatalogOwner owner, String materialId, MaterialScopeReference scope) { return false; }
        @Override public boolean removeScope(CatalogOwner owner, String materialId, String linkId) { return false; }
    }
}
