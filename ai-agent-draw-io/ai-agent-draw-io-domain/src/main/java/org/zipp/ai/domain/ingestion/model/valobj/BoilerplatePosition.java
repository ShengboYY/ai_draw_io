package org.zipp.ai.domain.ingestion.model.valobj;

/** Page-local position hint; document-level repetition decides whether a candidate is boilerplate. */
public enum BoilerplatePosition {
    NONE,
    HEADER_CANDIDATE,
    FOOTER_CANDIDATE
}
