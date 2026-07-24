package org.zipp.ai.domain.material.service;

import org.zipp.ai.domain.material.model.valobj.CatalogErrorCode;
import org.zipp.ai.domain.material.model.valobj.CatalogOperationException;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.material.model.valobj.MaterialDownloadFile;
import org.zipp.ai.domain.material.port.MaterialDownloadPort;

import java.util.Objects;

/** Resolves an exact downloadable version through the owner-fenced content port. */
public final class MaterialDownloadService {
    private final MaterialDownloadPort downloads;

    public MaterialDownloadService(MaterialDownloadPort downloads) {
        this.downloads = Objects.requireNonNull(downloads, "downloads");
    }

    public MaterialDownloadFile download(CatalogOwner owner, String materialId, String versionId) {
        return downloads.find(Objects.requireNonNull(owner, "owner"),
                        required(materialId, "materialId"), required(versionId, "versionId"))
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.VERSION_NOT_FOUND));
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
