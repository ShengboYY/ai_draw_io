package org.zipp.ai.trigger.http;

import org.zipp.ai.api.dto.MaterialCatalogCardDTO;
import org.zipp.ai.api.dto.MaterialCatalogDetailsDTO;
import org.zipp.ai.domain.material.model.valobj.MaterialCatalogDetails;
import org.zipp.ai.domain.material.model.valobj.MaterialCatalogItem;

import java.util.List;

/** Maps owner-safe catalog projections without exposing storage coordinates. */
final class MaterialCatalogDtoMapper {
    private MaterialCatalogDtoMapper() {
    }

    static MaterialCatalogDetailsDTO details(MaterialCatalogDetails source) {
        List<MaterialCatalogDetailsDTO.VersionDTO> versions = source.versions().stream().map(version ->
                new MaterialCatalogDetailsDTO.VersionDTO(version.versionId(), version.versionNo(),
                        version.detectedMime(), version.byteSize(), version.pageCount(),
                        version.processingStatus().name(), version.progress(),
                        version.createdAt())).toList();
        List<MaterialCatalogDetailsDTO.ScopeDTO> scopes = source.scopes().stream().map(scope ->
                new MaterialCatalogDetailsDTO.ScopeDTO(scope.linkId(), scope.scopeType().name(),
                        scope.scopeKey())).toList();
        return new MaterialCatalogDetailsDTO(card(source.material()), versions, scopes);
    }

    static MaterialCatalogCardDTO card(MaterialCatalogItem source) {
        return new MaterialCatalogCardDTO(source.materialId(), source.kind().name(), source.displayName(),
                source.retentionClass().name(), source.lifecycleState().name(), source.latestVersionId(),
                source.latestVersionNo(), source.processingStatus().name(), source.progress(),
                source.searchStatus().name(), source.pageCount(), source.updatedAt());
    }
}
