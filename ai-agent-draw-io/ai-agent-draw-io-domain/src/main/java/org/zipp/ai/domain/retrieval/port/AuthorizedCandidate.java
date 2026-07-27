package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;

/** Candidate re-authorized in MySQL and pinned to an exact immutable retrieval artifact. */
public record AuthorizedCandidate(String chunkId, String evidenceId, String materialId,
                                  String versionId, String revisionId, String modality,
                                  int pageNumber, double qualityScore, StoredArtifact displayArtifact,
                                  String sourceLabel) {
}
