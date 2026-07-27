package org.zipp.ai.domain.retrieval.model.aggregate;

import org.zipp.ai.domain.retrieval.model.entity.EvidenceUnit;
import org.zipp.ai.domain.retrieval.model.entity.RetrievalChunkEvidence;
import org.zipp.ai.domain.retrieval.model.valobj.ChunkEvidenceRole;
import org.zipp.ai.domain.retrieval.model.valobj.EvidenceModality;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalChunkType;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalIndexMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RetrievalChunk {

    private final String id;
    private final String ownerKey;
    private final String versionId;
    private final String revisionId;
    private final RetrievalChunkType chunkType;
    private final EvidenceModality modality;
    private final boolean citable;
    private final RetrievalIndexMode indexMode;
    private final List<RetrievalChunkEvidence> evidenceMappings = new ArrayList<>();
    private boolean active;

    private RetrievalChunk(String id, String ownerKey, String versionId, String revisionId,
                           RetrievalChunkType chunkType, EvidenceModality modality,
                           boolean citable, RetrievalIndexMode indexMode) {
        this.id = requireText(id, "id");
        this.ownerKey = requireText(ownerKey, "ownerKey");
        this.versionId = requireText(versionId, "versionId");
        this.revisionId = requireText(revisionId, "revisionId");
        this.chunkType = Objects.requireNonNull(chunkType, "chunkType");
        this.modality = Objects.requireNonNull(modality, "modality");
        this.indexMode = Objects.requireNonNull(indexMode, "indexMode");
        if (citable && !chunkType.allowsCitation()) {
            throw new IllegalArgumentException("bridge and profile chunks cannot be cited");
        }
        this.citable = citable;
    }

    public static RetrievalChunk create(String id, String ownerKey, String versionId, String revisionId,
                                        RetrievalChunkType chunkType, EvidenceModality modality,
                                        boolean citable, RetrievalIndexMode indexMode) {
        return new RetrievalChunk(id, ownerKey, versionId, revisionId, chunkType, modality, citable, indexMode);
    }

    public void attachEvidence(EvidenceUnit evidence, ChunkEvidenceRole role, int ordinal) {
        if (active) {
            throw new IllegalStateException("active retrieval chunks are immutable");
        }
        EvidenceUnit unit = Objects.requireNonNull(evidence, "evidence");
        if (!ownerKey.equals(unit.ownerKey())
                || !versionId.equals(unit.versionId())
                || !revisionId.equals(unit.revisionId())) {
            throw new IllegalArgumentException("chunk evidence must share owner, version, and revision");
        }
        evidenceMappings.add(new RetrievalChunkEvidence(unit, role, ordinal));
    }

    public void activate() {
        if (active) {
            return;
        }
        if (citable && evidenceMappings.stream().noneMatch(mapping -> mapping.role() == ChunkEvidenceRole.PRIMARY)) {
            throw new IllegalStateException("a citable chunk requires primary evidence");
        }
        active = true;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String id() { return id; }
    public String ownerKey() { return ownerKey; }
    public String versionId() { return versionId; }
    public String revisionId() { return revisionId; }
    public RetrievalChunkType chunkType() { return chunkType; }
    public EvidenceModality modality() { return modality; }
    public boolean citable() { return citable; }
    public RetrievalIndexMode indexMode() { return indexMode; }
    public boolean active() { return active; }
    public List<RetrievalChunkEvidence> evidenceMappings() {
        return Collections.unmodifiableList(evidenceMappings);
    }
}
