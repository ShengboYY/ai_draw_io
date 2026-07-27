package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.*;
import org.zipp.ai.domain.retrieval.port.RequestSourceSnapshotStore;
import org.zipp.ai.infrastructure.dao.retrieval.IRequestSourceSnapshotMapper;
import org.zipp.ai.infrastructure.dao.retrieval.RequestSourceSnapshotItemPO;
import org.zipp.ai.infrastructure.dao.retrieval.RequestSourceSnapshotPO;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Insert-only persistence for run source snapshots; duplicate run IDs are verified by the domain service. */
@Repository
public class MySqlRequestSourceSnapshotStore implements RequestSourceSnapshotStore {
    private final IRequestSourceSnapshotMapper mapper;

    public MySqlRequestSourceSnapshotStore(IRequestSourceSnapshotMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Optional<StoredSnapshot> find(CatalogOwner owner, String runId) {
        RequestSourceSnapshotPO header = mapper.selectSnapshot(owner, runId);
        if (header == null) return Optional.empty();
        List<ResolvedSource> sources = mapper.selectSnapshotItems(owner, runId).stream()
                .map(this::source).toList();
        return Optional.of(new StoredSnapshot(header.getDeclarationFingerprint(),
                new ResolvedSourceSet(SourceMode.valueOf(header.getSourceMode()), sources,
                        header.getProcessingSourceCount(), header.getUnavailableSourceCount())));
    }

    @Override
    @Transactional
    public void save(CatalogOwner owner, String runId, String declarationFingerprint,
                     ResolvedSourceSet sources) {
        if (mapper.insertSnapshot(owner, runId, declarationFingerprint, sources) != 1) return;
        for (int index = 0; index < sources.sources().size(); index++) {
            mapper.insertSnapshotItem(runId, index, sources.sources().get(index));
        }
    }

    private ResolvedSource source(RequestSourceSnapshotItemPO row) {
        return new ResolvedSource(row.getMaterialId(), row.getVersionId(), row.getRevisionId(),
                row.getKind(), row.getDisplayName(), MaterialScopeType.valueOf(row.getScopeType()), row.getScopeKey(),
                row.getState(), RequestSourceOrigin.valueOf(row.getOrigin()), row.isHasText(),
                row.isHasVisual(), row.isPinned(), row.isCountsAsProcessingSource());
    }
}
