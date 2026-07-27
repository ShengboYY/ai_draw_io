package org.zipp.ai.trigger.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.zipp.ai.api.dto.*;
import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.service.MaterialPreviewService;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/materials")
@ConditionalOnProperty(name = {"app.material-catalog.enabled", "app.material-preview.enabled"},
        havingValue = "true")
public class MaterialPreviewController {
    private final CurrentOwnerHttpResolver ownerResolver;
    private final MaterialPreviewService previews;

    public MaterialPreviewController(CurrentOwnerHttpResolver ownerResolver,
                                     MaterialPreviewService previews) {
        this.ownerResolver = ownerResolver;
        this.previews = previews;
    }

    @GetMapping("/{materialId}/versions/{versionId}/pages")
    public Response<MaterialPageSetDTO> pages(@PathVariable String materialId,
                                              @PathVariable String versionId,
                                              @RequestParam(required = false) String revisionId) {
        return CatalogControllerSupport.execute(() -> pageSet(previews.findPages(
                CatalogControllerSupport.requiredOwner(ownerResolver), materialId, versionId, revisionId)));
    }

    @GetMapping("/{materialId}/versions/{versionId}/pages/{pageNo}/preview")
    public ResponseEntity<?> preview(@PathVariable String materialId,
                                     @PathVariable String versionId,
                                     @PathVariable int pageNo,
                                     @RequestParam(required = false) String revisionId) {
        try {
            MaterialPreviewImage image = previews.preview(CatalogControllerSupport.requiredOwner(ownerResolver),
                    materialId, versionId, revisionId, pageNo);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(image.contentType()))
                    .contentLength(image.bytes().length)
                    .eTag('"' + image.contentSha256() + '"')
                    .cacheControl(CacheControl.noStore().cachePrivate().mustRevalidate())
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.inline().filename("page-" + pageNo + ".png").build().toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(image.bytes());
        } catch (CatalogOperationException e) {
            HttpStatus status = switch (e.code()) {
                case MATERIAL_NOT_FOUND, VERSION_NOT_FOUND, REVISION_NOT_FOUND, PAGE_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case REPROCESS_IN_PROGRESS, CATALOG_CONFLICT -> HttpStatus.CONFLICT;
                case PREVIEW_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                default -> HttpStatus.BAD_REQUEST;
            };
            return ResponseEntity.status(status).body(CatalogControllerSupport.failure(e.code().name()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(CatalogControllerSupport.failure("CATALOG_REQUEST_INVALID"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(CatalogControllerSupport.failure("TRANSIENT_DEPENDENCY"));
        }
    }

    @PostMapping("/{materialId}/reprocess")
    public Response<MaterialReprocessResponseDTO> reprocess(
            @PathVariable String materialId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return CatalogControllerSupport.execute(() -> result(previews.reprocess(
                CatalogControllerSupport.requiredOwner(ownerResolver), materialId, idempotencyKey)));
    }

    @PutMapping("/{materialId}/excluded-pages")
    public Response<MaterialReprocessResponseDTO> replaceExcludedPages(
            @PathVariable String materialId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ExcludedPagesRequestDTO body) {
        return CatalogControllerSupport.execute(() -> result(previews.replaceExcludedPages(
                CatalogControllerSupport.requiredOwner(ownerResolver), materialId,
                requiredPages(body), idempotencyKey)));
    }

    private MaterialPageSetDTO pageSet(MaterialPageSet source) {
        return new MaterialPageSetDTO(source.materialId(), source.versionId(), source.revisionId(),
                source.revisionNo(), source.processingStatus().name(), source.progress(),
                source.excludedPages(), source.pages().stream().map(page -> new MaterialPageDTO(
                page.pageNo(), page.width(), page.height(), page.nativeTextStatus(), page.ocrStatus(),
                page.ocrQuality(), page.visualStatus(), page.errorCode(), page.canonicalAvailable(),
                page.previewAvailable())).toList());
    }

    private MaterialReprocessResponseDTO result(MaterialReprocessResult source) {
        return new MaterialReprocessResponseDTO(source.materialId(), source.versionId(), source.revisionId(),
                source.revisionNo(), source.processingStatus().name(), source.reused());
    }

    private Set<Integer> requiredPages(ExcludedPagesRequestDTO body) {
        if (body == null || body.pageNumbers() == null) {
            throw new IllegalArgumentException("pageNumbers is required");
        }
        return body.pageNumbers();
    }
}
