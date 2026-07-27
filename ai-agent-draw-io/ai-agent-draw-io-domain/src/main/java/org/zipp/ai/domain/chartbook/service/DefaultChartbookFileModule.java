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
        ChartbookView chartbook = activeChartbook(command.owner(), command.chartbookId());
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

        return new ChartbookFileResult(activeChartbook(command.owner(), command.chartbookId()),
                materials.findMaterial(command.owner(), command.materialId()));
    }

    @Override
    public ChartbookFileResult remove(RemoveChartbookFileCommand command) {
        command.owner().requireRegisteredUser();
        activeChartbook(command.owner(), command.chartbookId());
        MaterialCatalogDetails file = materials.findMaterial(command.owner(), command.materialId());
        file.findScope(MaterialScopeType.CHARTBOOK, command.chartbookId()).ifPresent(scope -> {
            if (file.scopes().size() == 1) {
                // The final association and active lifecycle state change in one catalog transaction.
                materials.removeLastScopeAndTrash(
                        command.owner(), command.materialId(), scope.linkId());
            } else {
                materials.removeScope(command.owner(), command.materialId(), scope.linkId());
            }
        });
        // Exact scope identity makes a repeated delete return the same final view.
        return new ChartbookFileResult(activeChartbook(command.owner(), command.chartbookId()),
                materials.findMaterial(command.owner(), command.materialId()));
    }

    private ChartbookView activeChartbook(CatalogOwner owner, String chartbookId) {
        ChartbookView chartbook = chartbooks.find(owner, chartbookId)
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.CHARTBOOK_NOT_FOUND));
        if (chartbook.status() != ChartbookStatus.ACTIVE) {
            throw new CatalogOperationException(CatalogErrorCode.CHARTBOOK_ARCHIVED);
        }
        return chartbook;
    }
}
