package org.zipp.ai.trigger.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.zipp.ai.api.dto.*;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.service.MaterialCatalogService;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/materials")
@ConditionalOnProperty(name = "app.material-catalog.enabled", havingValue = "true")
public class MaterialCatalogController {
    private final CurrentOwnerHttpResolver ownerResolver;
    private final MaterialCatalogService materials;

    public MaterialCatalogController(CurrentOwnerHttpResolver ownerResolver,
                                     MaterialCatalogService materials) {
        this.ownerResolver = ownerResolver;
        this.materials = materials;
    }

    @GetMapping
    public Response<MaterialCatalogPageDTO> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String lifecycleState,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return CatalogControllerSupport.execute(() -> {
            MaterialCatalogPage page = materials.findMaterials(new MaterialCatalogQuery(
                    CatalogControllerSupport.requiredOwner(ownerResolver), query,
                    enumValue(MaterialLifecycleState.class, lifecycleState, MaterialLifecycleState.ACTIVE),
                    limit, offset));
            return new MaterialCatalogPageDTO(page.items().stream().map(this::card).toList(),
                    page.total(), page.limit(), page.offset());
        });
    }

    @GetMapping("/{materialId}")
    public Response<MaterialCatalogDetailsDTO> details(@PathVariable String materialId) {
        return CatalogControllerSupport.execute(() -> details(materials.findMaterial(
                CatalogControllerSupport.requiredOwner(ownerResolver), materialId)));
    }

    @PostMapping("/{materialId}/scope-links")
    public Response<MaterialCatalogDetailsDTO> addScope(@PathVariable String materialId,
                                                        @RequestBody MaterialScopeRequestDTO body) {
        return CatalogControllerSupport.execute(() -> details(materials.addScope(new MaterialScopeCommand(
                CatalogControllerSupport.requiredOwner(ownerResolver), materialId,
                enumValue(MaterialScopeType.class, body.scopeType(), null), body.scopeId()))));
    }

    @DeleteMapping("/{materialId}/scope-links/{linkId}")
    public Response<MaterialCatalogDetailsDTO> removeScope(@PathVariable String materialId,
                                                           @PathVariable String linkId) {
        return CatalogControllerSupport.execute(() -> details(materials.removeScope(
                CatalogControllerSupport.requiredOwner(ownerResolver), materialId, linkId)));
    }

    private MaterialCatalogDetailsDTO details(MaterialCatalogDetails source) {
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

    private MaterialCatalogCardDTO card(MaterialCatalogItem source) {
        return new MaterialCatalogCardDTO(source.materialId(), source.kind().name(), source.displayName(),
                source.retentionClass().name(), source.lifecycleState().name(), source.latestVersionId(),
                source.latestVersionNo(), source.processingStatus().name(), source.progress(),
                source.pageCount(), source.updatedAt());
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) {
            if (fallback != null) return fallback;
            throw new IllegalArgumentException("enum value is required");
        }
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }
}
