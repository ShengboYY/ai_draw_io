package org.zipp.ai.domain.chartbook.service;

import org.zipp.ai.domain.chartbook.model.aggregate.Chartbook;
import org.zipp.ai.domain.chartbook.model.valobj.*;
import org.zipp.ai.domain.chartbook.port.ChartbookCatalogPort;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.CatalogIdFactory;
import org.zipp.ai.domain.material.service.MaterialCatalogService;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/** Application service coordinating chartbook membership without transferring material ownership. */
public final class ChartbookCatalogService {
    private final ChartbookCatalogPort chartbooks;
    private final MaterialCatalogService materials;
    private final CatalogIdFactory ids;
    private final Clock clock;

    public ChartbookCatalogService(ChartbookCatalogPort chartbooks, MaterialCatalogService materials,
                                   CatalogIdFactory ids, Clock clock) {
        this.chartbooks = Objects.requireNonNull(chartbooks, "chartbooks");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ChartbookView create(CreateChartbookCommand command) {
        command.owner().requireRegisteredUser();
        Chartbook chartbook = Chartbook.create(ids.nextChartbookId(), command.owner().ownerType(),
                command.owner().ownerKey(), command.name());
        return chartbooks.createOrFind(chartbook, command.idempotencyKey(), clock.instant());
    }

    public List<ChartbookView> findAll(CatalogOwner owner) {
        owner.requireRegisteredUser();
        return chartbooks.findAll(owner);
    }

    public ChartbookView find(CatalogOwner owner, String chartbookId) {
        owner.requireRegisteredUser();
        return chartbooks.find(owner, required(chartbookId, "chartbookId"))
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.CHARTBOOK_NOT_FOUND));
    }

    public ChartbookView rename(CatalogOwner owner, String chartbookId, String newName) {
        Chartbook aggregate = aggregate(find(owner, chartbookId));
        aggregate.rename(newName);
        if (!chartbooks.rename(owner, aggregate.id(), aggregate.name())) conflict();
        return find(owner, chartbookId);
    }

    public void archive(CatalogOwner owner, String chartbookId) {
        Chartbook aggregate = aggregate(find(owner, chartbookId));
        aggregate.archive();
        if (!chartbooks.archive(owner, aggregate.id())) conflict();
    }

    public ChartbookView addMaterial(CatalogOwner owner, String chartbookId, String materialId) {
        Chartbook aggregate = aggregate(find(owner, chartbookId));
        materials.findMaterial(owner, materialId);
        aggregate.shareMaterial(materialId, owner.ownerKey());
        materials.addScope(new MaterialScopeCommand(owner, materialId,
                MaterialScopeType.CHARTBOOK, chartbookId));
        return find(owner, chartbookId);
    }

    public ChartbookView removeMaterial(CatalogOwner owner, String chartbookId, String materialId) {
        Chartbook aggregate = aggregate(find(owner, chartbookId));
        MaterialCatalogDetails material = materials.findMaterial(owner, materialId);
        MaterialScopeReference link = material.findScope(MaterialScopeType.CHARTBOOK, chartbookId)
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.SCOPE_TARGET_NOT_FOUND));
        aggregate.removeSharedMaterial(materialId);
        materials.removeScope(owner, materialId, link.linkId());
        return find(owner, chartbookId);
    }

    public ChartbookView assignDiagram(CatalogOwner owner, String diagramId, String chartbookId) {
        Chartbook aggregate = aggregate(find(owner, chartbookId));
        aggregate.addDiagram(diagramId);
        if (!chartbooks.assignDiagram(owner, required(diagramId, "diagramId"), aggregate.id())) {
            throw new CatalogOperationException(CatalogErrorCode.DIAGRAM_NOT_FOUND);
        }
        return find(owner, chartbookId);
    }

    public void removeDiagram(CatalogOwner owner, String diagramId) {
        owner.requireRegisteredUser();
        if (!chartbooks.removeDiagram(owner, required(diagramId, "diagramId"))) {
            throw new CatalogOperationException(CatalogErrorCode.DIAGRAM_NOT_FOUND);
        }
    }

    private Chartbook aggregate(ChartbookView view) {
        if (view.status() != ChartbookStatus.ACTIVE) {
            throw new CatalogOperationException(CatalogErrorCode.CHARTBOOK_ARCHIVED);
        }
        return Chartbook.rehydrate(view.chartbookId(), view.ownerKey(), view.name(), view.status(),
                view.diagramIds(), view.materialIds());
    }

    private void conflict() {
        throw new CatalogOperationException(CatalogErrorCode.CATALOG_CONFLICT);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
