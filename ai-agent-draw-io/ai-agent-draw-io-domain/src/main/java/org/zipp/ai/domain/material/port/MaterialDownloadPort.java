package org.zipp.ai.domain.material.port;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialDownloadFile;

import java.util.Optional;

/** Loads one owner-authorized immutable original without exposing its storage address. */
public interface MaterialDownloadPort {
    Optional<MaterialDownloadFile> find(CatalogOwner owner, String materialId, String versionId);
}
