package org.zipp.ai.trigger.http;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zipp.ai.domain.material.model.valobj.CatalogOperationException;
import org.zipp.ai.domain.material.model.valobj.MaterialDownloadFile;
import org.zipp.ai.domain.material.service.MaterialDownloadService;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/materials")
@ConditionalOnProperty(name = "app.material-catalog.enabled", havingValue = "true")
public class MaterialDownloadController {
    private final CurrentOwnerHttpResolver ownerResolver;
    private final MaterialDownloadService downloads;

    public MaterialDownloadController(CurrentOwnerHttpResolver ownerResolver,
                                      MaterialDownloadService downloads) {
        this.ownerResolver = ownerResolver;
        this.downloads = downloads;
    }

    @GetMapping("/{materialId}/versions/{versionId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String materialId,
                                           @PathVariable String versionId) {
        try {
            MaterialDownloadFile file = downloads.download(
                    CatalogControllerSupport.requiredOwner(ownerResolver), materialId, versionId);
            byte[] bytes = file.responseBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(file.contentType()))
                    .contentLength(bytes.length)
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(file.fileName(), StandardCharsets.UTF_8)
                            .build().toString())
                    .body(bytes);
        } catch (CatalogOperationException | IllegalArgumentException e) {
            // A uniform not-found response prevents version and owner enumeration.
            return ResponseEntity.notFound().build();
        }
    }
}
