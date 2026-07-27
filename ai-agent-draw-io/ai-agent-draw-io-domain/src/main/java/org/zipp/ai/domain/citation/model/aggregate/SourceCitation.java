package org.zipp.ai.domain.citation.model.aggregate;

import org.zipp.ai.domain.citation.model.entity.CitationEvidence;
import org.zipp.ai.domain.citation.model.entity.CitationSourceTombstone;
import org.zipp.ai.domain.citation.model.valobj.CitationState;
import org.zipp.ai.domain.citation.model.valobj.SupportType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SourceCitation {

    private final String id;
    private final String ownerKey;
    private final String diagramId;
    private final long canvasVersion;
    private final String cellId;
    private final SupportType supportType;
    private final Map<String, CitationEvidence> liveEvidence = new LinkedHashMap<>();
    private final List<CitationSourceTombstone> tombstones = new ArrayList<>();
    private CitationState state;

    private SourceCitation(String id, String ownerKey, String diagramId, long canvasVersion,
                           String cellId, SupportType supportType) {
        this.id = requireText(id, "id");
        this.ownerKey = requireText(ownerKey, "ownerKey");
        this.diagramId = requireText(diagramId, "diagramId");
        if (canvasVersion < 1) {
            throw new IllegalArgumentException("canvasVersion must be positive");
        }
        this.canvasVersion = canvasVersion;
        this.cellId = requireText(cellId, "cellId");
        this.supportType = Objects.requireNonNull(supportType, "supportType");
        // Evidence support becomes verified only after the prepared-bundle bindings exist.
        this.state = supportType == SupportType.EVIDENCE ? CitationState.NEEDS_REVIEW : null;
    }

    public static SourceCitation forDiagramCell(String id, String ownerKey, String diagramId,
                                                long canvasVersion, String cellId, SupportType supportType) {
        return new SourceCitation(id, ownerKey, diagramId, canvasVersion, cellId, supportType);
    }

    public void bindEvidence(CitationEvidence evidence) {
        if (supportType != SupportType.EVIDENCE) {
            throw new IllegalStateException("only evidence-supported citations can bind evidence");
        }
        CitationEvidence binding = Objects.requireNonNull(evidence, "evidence");
        requireText(binding.evidenceId(), "evidenceId");
        requireText(binding.versionId(), "versionId");
        requireText(binding.revisionId(), "revisionId");
        requireText(binding.citationKey(), "citationKey");
        liveEvidence.putIfAbsent(binding.evidenceId(), binding);
    }

    public void markNeedsReview() {
        requireEvidenceSupport();
        if (state == CitationState.SOURCE_UNAVAILABLE) {
            throw new IllegalStateException("an unavailable citation cannot be reviewed");
        }
        state = CitationState.NEEDS_REVIEW;
    }

    public void verify() {
        requireEvidenceSupport();
        if (liveEvidence.isEmpty()) {
            throw new IllegalStateException("citation has no live evidence");
        }
        state = CitationState.VERIFIED;
    }

    public void tombstoneEvidence(String evidenceId, String opaqueMaterialId,
                                  String opaqueVersionId, int versionNo) {
        requireEvidenceSupport();
        CitationEvidence removed = liveEvidence.remove(requireText(evidenceId, "evidenceId"));
        if (removed == null) {
            throw new IllegalArgumentException("evidence is not bound to this citation");
        }
        // Tombstones deliberately retain only opaque identity, never names, pages, excerpts, or object keys.
        tombstones.add(new CitationSourceTombstone(
                requireText(opaqueMaterialId, "opaqueMaterialId"),
                requireText(opaqueVersionId, "opaqueVersionId"), versionNo));
        state = liveEvidence.isEmpty()
                ? CitationState.SOURCE_UNAVAILABLE
                : CitationState.PARTIAL_SOURCE_UNAVAILABLE;
    }

    private void requireEvidenceSupport() {
        if (supportType != SupportType.EVIDENCE) {
            throw new IllegalStateException("support type does not use citation state");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String id() { return id; }
    public String ownerKey() { return ownerKey; }
    public String diagramId() { return diagramId; }
    public long canvasVersion() { return canvasVersion; }
    public String cellId() { return cellId; }
    public SupportType supportType() { return supportType; }
    public CitationState state() { return state; }
    public Map<String, CitationEvidence> liveEvidence() { return Collections.unmodifiableMap(liveEvidence); }
    public List<CitationSourceTombstone> tombstones() { return Collections.unmodifiableList(tombstones); }
}
