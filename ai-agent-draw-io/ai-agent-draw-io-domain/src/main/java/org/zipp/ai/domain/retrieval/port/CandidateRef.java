package org.zipp.ai.domain.retrieval.port;

public record CandidateRef(String chunkId, String modality, double score) {
}
