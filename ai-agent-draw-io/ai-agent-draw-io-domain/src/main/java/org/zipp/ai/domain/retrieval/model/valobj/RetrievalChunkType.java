package org.zipp.ai.domain.retrieval.model.valobj;

public enum RetrievalChunkType {
    CONTENT(true),
    LIST_GROUP(true),
    TABLE_ROW_GROUP(true),
    VISUAL_DESCRIPTION(true),
    CAPTION_CONTEXT(true),
    PAGE_PARENT(true),
    SECTION_BRIDGE(false),
    DOCUMENT_PROFILE(false);

    private final boolean citable;

    RetrievalChunkType(boolean citable) {
        this.citable = citable;
    }

    public boolean allowsCitation() {
        return citable;
    }
}
