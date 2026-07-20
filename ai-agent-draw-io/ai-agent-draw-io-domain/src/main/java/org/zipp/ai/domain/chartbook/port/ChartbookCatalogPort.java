package org.zipp.ai.domain.chartbook.port;

import org.zipp.ai.domain.chartbook.model.aggregate.Chartbook;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookView;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence boundary for single-owner chartbooks and diagram membership. */
public interface ChartbookCatalogPort {
    ChartbookView createOrFind(Chartbook chartbook, String idempotencyKey, Instant createdAt);
    List<ChartbookView> findAll(CatalogOwner owner);
    Optional<ChartbookView> find(CatalogOwner owner, String chartbookId);
    boolean rename(CatalogOwner owner, String chartbookId, String name);
    boolean archive(CatalogOwner owner, String chartbookId);
    boolean assignDiagram(CatalogOwner owner, String diagramId, String chartbookId);
    boolean removeDiagram(CatalogOwner owner, String diagramId);
}
