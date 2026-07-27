package org.zipp.ai.domain.material.service;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionProfile;
import org.zipp.ai.domain.material.model.valobj.*;
import org.zipp.ai.domain.material.port.CatalogIdFactory;
import org.zipp.ai.domain.material.port.MaterialPageAccessPort;
import org.zipp.ai.domain.material.port.MaterialPreviewContentPort;

import java.time.Clock;
import java.util.Objects;
import java.util.Set;

public final class MaterialPreviewService {
    private final MaterialPageAccessPort pages;
    private final MaterialPreviewContentPort previews;
    private final MaterialRevisionPolicy revisionPolicy;
    private final ProcessingRevisionProfile targetProcessingProfile;
    private final CatalogIdFactory ids;
    private final Clock clock;

    public MaterialPreviewService(MaterialPageAccessPort pages, MaterialPreviewContentPort previews,
                                  MaterialRevisionPolicy revisionPolicy,
                                  ProcessingRevisionProfile targetProcessingProfile,
                                  CatalogIdFactory ids, Clock clock) {
        this.pages = Objects.requireNonNull(pages, "pages");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.revisionPolicy = Objects.requireNonNull(revisionPolicy, "revisionPolicy");
        this.targetProcessingProfile = Objects.requireNonNull(targetProcessingProfile,
                "targetProcessingProfile");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MaterialPageSet findPages(CatalogOwner owner, String materialId,
                                     String versionId, String revisionId) {
        owner.requireRegisteredUser();
        return pages.findPages(owner, required(materialId, "materialId"),
                        required(versionId, "versionId"), optional(revisionId))
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.REVISION_NOT_FOUND));
    }

    public MaterialPreviewImage preview(CatalogOwner owner, String materialId, String versionId,
                                        String revisionId, int pageNo) {
        owner.requireRegisteredUser();
        if (pageNo < 1) throw new IllegalArgumentException("pageNo must be positive");
        StoredArtifact artifact = pages.findPreviewArtifact(owner, required(materialId, "materialId"),
                        required(versionId, "versionId"), optional(revisionId), pageNo)
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.PAGE_NOT_FOUND));
        return previews.render(artifact);
    }

    public MaterialReprocessResult reprocess(CatalogOwner owner, String materialId, String idempotencyKey) {
        MaterialReprocessSnapshot source = snapshot(owner, materialId);
        return pages.createOrFindRevision(revisionPolicy.plan(owner, source, source.excludedPages(),
                idempotencyKey, targetProcessingProfile, ids, clock.instant()));
    }

    public MaterialReprocessResult replaceExcludedPages(CatalogOwner owner, String materialId,
                                                         Set<Integer> excludedPages,
                                                         String idempotencyKey) {
        MaterialReprocessSnapshot source = snapshot(owner, materialId);
        return pages.createOrFindRevision(revisionPolicy.plan(owner, source,
                Set.copyOf(Objects.requireNonNull(excludedPages, "excludedPages")),
                idempotencyKey, targetProcessingProfile, ids, clock.instant()));
    }

    private MaterialReprocessSnapshot snapshot(CatalogOwner owner, String materialId) {
        owner.requireRegisteredUser();
        return pages.findReprocessSnapshot(owner, required(materialId, "materialId"))
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.MATERIAL_NOT_FOUND));
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
