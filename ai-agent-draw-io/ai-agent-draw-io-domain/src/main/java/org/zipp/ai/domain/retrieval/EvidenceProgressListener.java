package org.zipp.ai.domain.retrieval;

@FunctionalInterface
public interface EvidenceProgressListener {
    EvidenceProgressListener NOOP = (stage, completed, total) -> { };
    void onProgress(String stage, int completed, int total);
}
