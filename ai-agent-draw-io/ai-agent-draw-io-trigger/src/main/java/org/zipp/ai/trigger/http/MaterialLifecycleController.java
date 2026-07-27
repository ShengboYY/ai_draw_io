package org.zipp.ai.trigger.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.zipp.ai.api.dto.*;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.service.MaterialDeletionService;
import org.zipp.ai.domain.material.service.MaterialLifecycleService;

import java.util.Locale;

@RestController
@RequestMapping("/api/v1/materials")
@ConditionalOnProperty(name = {"app.material-catalog.enabled", "app.material-lifecycle.enabled"},
        havingValue = "true")
public class MaterialLifecycleController {
    private final CurrentOwnerHttpResolver ownerResolver;
    private final MaterialLifecycleService lifecycle;
    private final MaterialDeletionService deletion;

    public MaterialLifecycleController(CurrentOwnerHttpResolver ownerResolver,
                                       MaterialLifecycleService lifecycle,
                                       MaterialDeletionService deletion) {
        this.ownerResolver = ownerResolver;
        this.lifecycle = lifecycle;
        this.deletion = deletion;
    }

    @PostMapping("/{materialId}/promote")
    public Response<MaterialLifecycleResponseDTO> promote(
            @PathVariable String materialId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody MaterialPromotionRequestDTO request) {
        return CatalogControllerSupport.execute(() -> result(lifecycle.promote(owner(), materialId,
                MaterialScopeType.valueOf(request.scopeType().trim().toUpperCase(Locale.ROOT)),
                request.scopeId(), idempotencyKey)));
    }

    @DeleteMapping("/{materialId}")
    public Response<MaterialLifecycleResponseDTO> remove(
            @PathVariable String materialId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return CatalogControllerSupport.execute(() -> result(
                lifecycle.remove(owner(), materialId, idempotencyKey)));
    }

    @PostMapping("/{materialId}/restore")
    public Response<MaterialLifecycleResponseDTO> restore(
            @PathVariable String materialId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return CatalogControllerSupport.execute(() -> result(
                lifecycle.restore(owner(), materialId, idempotencyKey)));
    }

    /** Extends temporary TTL only for an explicit user action that consumed this material. */
    @PostMapping("/{materialId}/meaningful-activity")
    public Response<MaterialLifecycleResponseDTO> meaningfulActivity(@PathVariable String materialId) {
        return CatalogControllerSupport.execute(() -> result(
                lifecycle.recordMeaningfulActivity(owner(), materialId)));
    }

    @GetMapping("/{materialId}/deletion-impact")
    public Response<MaterialDeletionImpactDTO> deletionImpact(@PathVariable String materialId) {
        return CatalogControllerSupport.execute(() -> {
            MaterialDeletionPreview preview = deletion.preview(owner(), materialId);
            MaterialDeletionImpact impact = preview.impact();
            return new MaterialDeletionImpactDTO(impact.materialId(), impact.lifecycleGeneration(),
                    impact.versionCount(), impact.diagramCount(), impact.chartbookCount(),
                    impact.citationCount(), impact.versionIds(), impact.pinnedSourceIds(),
                    impact.diagramIds(), impact.chartbookIds(), impact.citationIds(),
                    preview.deletionConfirmationToken(),
                    preview.confirmationExpiresAt());
        });
    }

    @DeleteMapping("/{materialId}/permanent")
    public Response<MaterialLifecycleResponseDTO> permanent(
            @PathVariable String materialId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Material-Generation") long expectedGeneration,
            @RequestHeader("X-Deletion-Confirmation") String confirmationToken) {
        return CatalogControllerSupport.execute(() -> result(deletion.confirm(owner(), materialId,
                expectedGeneration, confirmationToken, idempotencyKey)));
    }

    private CatalogOwner owner() {
        return CatalogControllerSupport.requiredOwner(ownerResolver);
    }

    private MaterialLifecycleResponseDTO result(MaterialLifecycleResult source) {
        return new MaterialLifecycleResponseDTO(source.materialId(), source.retentionClass().name(),
                source.lifecycleState().name(), source.lifecycleGeneration(),
                source.expiresAt(), source.trashExpiresAt());
    }
}
