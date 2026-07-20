package org.zipp.ai.domain.material.port;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.model.valobj.*;

import java.util.Optional;

/** Owner-fenced metadata and revision mutation boundary for material page access. */
public interface MaterialPageAccessPort {
    Optional<MaterialPageSet> findPages(CatalogOwner owner, String materialId,
                                        String versionId, String revisionId);
    Optional<StoredArtifact> findPreviewArtifact(CatalogOwner owner, String materialId,
                                                 String versionId, String revisionId, int pageNo);
    Optional<MaterialReprocessSnapshot> findReprocessSnapshot(CatalogOwner owner, String materialId);
    MaterialReprocessResult createOrFindRevision(MaterialReprocessPlan plan);
}
