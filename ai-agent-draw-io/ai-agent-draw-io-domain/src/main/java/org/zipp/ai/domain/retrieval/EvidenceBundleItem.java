package org.zipp.ai.domain.retrieval;

/** Display evidence safe for model consumption; storage and lease capabilities are never exposed. */
public record EvidenceBundleItem(String citationKey, String evidenceId, String materialId,
                                 String sourceLabel, int pageNumber, String modality, String text) {
}
