package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.chartbook.model.aggregate.Chartbook;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookStatus;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookView;
import org.zipp.ai.domain.chartbook.port.ChartbookCatalogPort;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.infrastructure.dao.material.IChartbookCatalogMapper;
import org.zipp.ai.infrastructure.dao.material.po.ChartbookCatalogPO;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** MySQL adapter for one-owner chartbooks and non-destructive diagram membership. */
@Repository
public class MySqlChartbookCatalogAdapter implements ChartbookCatalogPort {
    private final IChartbookCatalogMapper mapper;

    public MySqlChartbookCatalogAdapter(IChartbookCatalogMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    @Transactional
    public ChartbookView createOrFind(Chartbook chartbook, String idempotencyKey, Instant createdAt) {
        mapper.insertChartbook(chartbook.id(), chartbook.ownerKey(), chartbook.name(), idempotencyKey, createdAt);
        ChartbookCatalogPO persisted = mapper.selectByIdempotencyKey(chartbook.ownerKey(), idempotencyKey);
        if (persisted == null) throw new IllegalStateException("chartbook idempotency identity was not persisted");
        return view(persisted);
    }

    @Override
    public List<ChartbookView> findAll(CatalogOwner owner) {
        return mapper.selectAll(owner.ownerKey()).stream().map(this::view).toList();
    }

    @Override
    public Optional<ChartbookView> find(CatalogOwner owner, String chartbookId) {
        return Optional.ofNullable(mapper.selectOne(owner.ownerKey(), chartbookId)).map(this::view);
    }

    @Override
    public boolean rename(CatalogOwner owner, String chartbookId, String name) {
        return mapper.rename(owner.ownerKey(), chartbookId, name) == 1;
    }

    @Override
    @Transactional
    public boolean archive(CatalogOwner owner, String chartbookId) {
        if (mapper.archive(owner.ownerKey(), chartbookId) != 1) return false;
        // Diagrams survive chartbook deletion and immediately lose the archived book's source scope.
        mapper.detachDiagrams(owner.ownerKey(), chartbookId);
        return true;
    }

    @Override
    public boolean assignDiagram(CatalogOwner owner, String diagramId, String chartbookId) {
        return mapper.assignDiagram(owner.ownerKey(), diagramId, chartbookId) == 1;
    }

    @Override
    public boolean removeDiagram(CatalogOwner owner, String diagramId) {
        return mapper.removeDiagram(owner.ownerKey(), diagramId) == 1;
    }

    private ChartbookView view(ChartbookCatalogPO po) {
        Set<String> diagrams = Set.copyOf(mapper.selectDiagramIds(po.getOwnerKey(), po.getId()));
        Set<String> materials = Set.copyOf(mapper.selectMaterialIds(po.getOwnerKey(), po.getId()));
        return new ChartbookView(po.getId(), po.getOwnerKey(), po.getName(),
                ChartbookStatus.valueOf(po.getStatus()), diagrams, materials,
                po.getCreatedAt(), po.getUpdatedAt());
    }
}
