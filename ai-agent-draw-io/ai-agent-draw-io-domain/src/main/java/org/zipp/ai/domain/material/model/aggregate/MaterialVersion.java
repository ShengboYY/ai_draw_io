package org.zipp.ai.domain.material.model.aggregate;

import org.zipp.ai.domain.ingestion.model.aggregate.ProcessingRevision;
import org.zipp.ai.domain.ingestion.model.valobj.ProcessingRevisionState;

public final class MaterialVersion {

    private final String id;
    private final String materialId;
    private final String ownerKey;
    private final int versionNo;
    private final String contentBlobId;
    private final String contentSha256;
    private final long byteSize;
    private String activeRevisionId;

    private MaterialVersion(String id, String materialId, String ownerKey, int versionNo,
                            String contentBlobId, String contentSha256, long byteSize) {
        this.id = requireText(id, "id");
        this.materialId = requireText(materialId, "materialId");
        this.ownerKey = requireText(ownerKey, "ownerKey");
        if (versionNo < 1) {
            throw new IllegalArgumentException("versionNo must be positive");
        }
        this.versionNo = versionNo;
        this.contentBlobId = requireText(contentBlobId, "contentBlobId");
        this.contentSha256 = requireText(contentSha256, "contentSha256");
        if (byteSize < 0) {
            throw new IllegalArgumentException("byteSize cannot be negative");
        }
        this.byteSize = byteSize;
    }

    public static MaterialVersion create(String id, String materialId, String ownerKey, int versionNo,
                                         String contentBlobId, String contentSha256, long byteSize) {
        return new MaterialVersion(id, materialId, ownerKey, versionNo, contentBlobId, contentSha256, byteSize);
    }

    public void publish(ProcessingRevision revision) {
        if (!id.equals(revision.versionId())) {
            throw new IllegalArgumentException("revision belongs to another source version");
        }
        if (revision.state() != ProcessingRevisionState.READY
                && revision.state() != ProcessingRevisionState.PARTIAL_READY) {
            throw new IllegalArgumentException("only a published revision can become active");
        }
        // The active pointer is the sole mutable publication boundary; old revisions remain immutable.
        activeRevisionId = revision.id();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String id() { return id; }
    public String materialId() { return materialId; }
    public String ownerKey() { return ownerKey; }
    public int versionNo() { return versionNo; }
    public String contentBlobId() { return contentBlobId; }
    public String contentSha256() { return contentSha256; }
    public long byteSize() { return byteSize; }
    public String activeRevisionId() { return activeRevisionId; }
}
