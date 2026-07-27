package org.zipp.ai.domain.retrieval.port;

public interface EvidenceBlobStore {
    String readDisplayText(AuthorizedCandidate candidate, long maximumBytes);
}
