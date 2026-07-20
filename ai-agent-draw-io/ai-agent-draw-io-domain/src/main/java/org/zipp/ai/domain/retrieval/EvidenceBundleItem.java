package org.zipp.ai.domain.retrieval;

/** Display evidence safe for model consumption; storage and lease capabilities are never exposed. */
public record EvidenceBundleItem(String citationKey, String evidenceId, String materialId,
                                 String versionId, String revisionId, String sourceLabel,
                                 int pageNumber, String modality, String text,
                                 EvidenceSupportRole supportRole) {
    public EvidenceBundleItem {
        supportRole = supportRole == null ? EvidenceSupportRole.SUPPORT : supportRole;
    }

    /** Existing evidence units are citable; parent context must opt into CONTEXT_ONLY explicitly. */
    public EvidenceBundleItem(String citationKey, String evidenceId, String materialId,
                              String versionId, String revisionId, String sourceLabel,
                              int pageNumber, String modality, String text) {
        this(citationKey, evidenceId, materialId, versionId, revisionId, sourceLabel,
                pageNumber, modality, text, EvidenceSupportRole.SUPPORT);
    }
}
