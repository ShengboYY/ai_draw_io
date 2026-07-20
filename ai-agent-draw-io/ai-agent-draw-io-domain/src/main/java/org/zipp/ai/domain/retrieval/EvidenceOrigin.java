package org.zipp.ai.domain.retrieval;

/** Why a source entered this answer bundle; exposed without leaking storage identities. */
public enum EvidenceOrigin {
    EXISTING_REFERENCE,
    EXPLICIT,
    SEARCH,
    SUPPLEMENTAL
}
