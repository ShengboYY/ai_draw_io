package org.zipp.ai.domain.retrieval.model.entity;

import org.zipp.ai.domain.retrieval.model.valobj.EvidenceModality;
import org.zipp.ai.domain.retrieval.model.valobj.EvidenceSourceChannel;

import java.util.Objects;

public final class EvidenceUnit {

    private final String id;
    private final String ownerKey;
    private final String versionId;
    private final String revisionId;
    private final String pageId;
    private final EvidenceModality modality;
    private final EvidenceSourceChannel sourceChannel;
    private final String displayTextObjectKey;
    private final String visualObjectKey;

    private EvidenceUnit(String id, String ownerKey, String versionId, String revisionId, String pageId,
                         EvidenceModality modality, EvidenceSourceChannel sourceChannel,
                         String displayTextObjectKey, String visualObjectKey) {
        this.id = requireText(id, "id");
        this.ownerKey = requireText(ownerKey, "ownerKey");
        this.versionId = requireText(versionId, "versionId");
        this.revisionId = requireText(revisionId, "revisionId");
        this.pageId = requireText(pageId, "pageId");
        this.modality = Objects.requireNonNull(modality, "modality");
        this.sourceChannel = Objects.requireNonNull(sourceChannel, "sourceChannel");
        this.displayTextObjectKey = trimToNull(displayTextObjectKey);
        this.visualObjectKey = trimToNull(visualObjectKey);
        validateSource();
    }

    public static EvidenceUnit create(String id, String ownerKey, String versionId, String revisionId, String pageId,
                                      EvidenceModality modality, EvidenceSourceChannel sourceChannel,
                                      String displayTextObjectKey, String visualObjectKey) {
        return new EvidenceUnit(id, ownerKey, versionId, revisionId, pageId, modality, sourceChannel,
                displayTextObjectKey, visualObjectKey);
    }

    private void validateSource() {
        if (modality == EvidenceModality.VISUAL) {
            if (sourceChannel != EvidenceSourceChannel.VISUAL || visualObjectKey == null) {
                throw new IllegalArgumentException("visual evidence must point to an original visual object");
            }
            return;
        }
        if (sourceChannel == EvidenceSourceChannel.VISUAL || displayTextObjectKey == null) {
            throw new IllegalArgumentException("textual evidence must point to native or OCR display text");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String id() { return id; }
    public String ownerKey() { return ownerKey; }
    public String versionId() { return versionId; }
    public String revisionId() { return revisionId; }
    public String pageId() { return pageId; }
    public EvidenceModality modality() { return modality; }
    public EvidenceSourceChannel sourceChannel() { return sourceChannel; }
    public String displayTextObjectKey() { return displayTextObjectKey; }
    public String visualObjectKey() { return visualObjectKey; }
}
