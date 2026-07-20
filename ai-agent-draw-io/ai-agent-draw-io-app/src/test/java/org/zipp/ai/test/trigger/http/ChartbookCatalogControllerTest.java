package org.zipp.ai.test.trigger.http;

import org.junit.jupiter.api.Test;
import org.zipp.ai.api.dto.ChartbookRequestDTO;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.chartbook.model.aggregate.Chartbook;
import org.zipp.ai.domain.chartbook.model.valobj.*;
import org.zipp.ai.domain.chartbook.port.ChartbookCatalogPort;
import org.zipp.ai.domain.chartbook.service.ChartbookCatalogService;
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
        ChartbookCatalogService service = new ChartbookCatalogService(chartbooks, materials,
                prefix -> "book_1", Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        ChartbookCatalogController controller = new ChartbookCatalogController(resolver, service);

        var response = controller.create("request_1", new ChartbookRequestDTO("Agile"));

        assertEquals("user_server", chartbooks.created.ownerKey());
        assertEquals("request_1", chartbooks.idempotencyKey);
        assertEquals("0000", response.getCode());
        assertEquals("book_1", response.getData().chartbookId());
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
