package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.ResolvedSourceSet;

import java.util.Optional;

public interface RequestSourceSnapshotStore {
    Optional<StoredSnapshot> find(CatalogOwner owner, String runId);
    void save(CatalogOwner owner, String runId, String declarationFingerprint, ResolvedSourceSet sources);

    record StoredSnapshot(String declarationFingerprint, ResolvedSourceSet sources) { }
}
