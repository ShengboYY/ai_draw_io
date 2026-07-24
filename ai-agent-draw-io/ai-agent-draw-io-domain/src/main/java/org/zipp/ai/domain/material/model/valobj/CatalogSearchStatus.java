package org.zipp.ai.domain.material.model.valobj;

/** User-safe dense-search readiness, independent from file processing readiness. */
public enum CatalogSearchStatus {
    NOT_APPLICABLE,
    PENDING,
    INDEXING,
    SEARCHABLE,
    SEARCH_LIMITED,
    INDEX_FAILED
}
