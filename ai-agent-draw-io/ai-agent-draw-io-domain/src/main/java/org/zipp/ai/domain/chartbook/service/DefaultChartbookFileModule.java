package org.zipp.ai.domain.chartbook.service;

import org.zipp.ai.domain.chartbook.model.valobj.*;
import org.zipp.ai.domain.chartbook.port.ChartbookCatalogPort;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.service.MaterialCatalogService;
import org.zipp.ai.domain.material.service.MaterialLifecycleService;

import java.util.Objects;

/** Coordinates retention and Chartbook scope without copying Material content or versions. */
public final class DefaultChartbookFileModule implements ChartbookFileModule {
    private final ChartbookCatalogPort chartbooks;
    private final MaterialCatalogService materials;
    private final MaterialLifecycleService lifecycle;

    public DefaultChartbookFileModule(ChartbookCatalogPort chartbooks,
                                      MaterialCatalogService materials,
                                      MaterialLifecycleService lifecycle) {
        this.chartbooks = Objects.requireNonNull(chartbooks, "chartbooks");
        this.materials = Objects.requireNonNull(materials, "materials");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public ChartbookFileResult add(AddChartbookFileCommand command) {
        command.owner().requireRegisteredUser();
        ChartbookView chartbook = activeChartbook(command);
        MaterialCatalogDetails file = materials.findMaterial(command.owner(), command.materialId());
        if (file.material().lifecycleState() != MaterialLifecycleState.ACTIVE) {
            throw new CatalogOperationException(CatalogErrorCode.MATERIAL_NOT_ACTIVE);
        }

        boolean alreadyShared = file.findScope(MaterialScopeType.CHARTBOOK, command.chartbookId()).isPresent();
        if (!alreadyShared && file.material().retentionClass() == RetentionClass.TEMPORARY) {
            // The lifecycle transaction changes retention and inserts the durable scope together.
            lifecycle.promote(command.owner(), command.materialId(), MaterialScopeType.CHARTBOOK,
                    command.chartbookId(), command.idempotencyKey());
        } else if (!alreadyShared) {
            materials.addScope(new MaterialScopeCommand(command.owner(), command.materialId(),
                    MaterialScopeType.CHARTBOOK, command.chartbookId()));
        }

        return new ChartbookFileResult(activeChartbook(command),
                materials.findMaterial(command.owner(), command.materialId()));
    }

    private ChartbookView activeChartbook(AddChartbookFileCommand command) {
        ChartbookView chartbook = chartbooks.find(command.owner(), command.chartbookId())
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.CHARTBOOK_NOT_FOUND));
        if (chartbook.status() != ChartbookStatus.ACTIVE) {
            throw new CatalogOperationException(CatalogErrorCode.CHARTBOOK_ARCHIVED);
        }
        return chartbook;
    }
}
