package org.zipp.ai.domain.retrieval.projection;

/** Exact local tokenizer contract; implementations must count without vendor-side truncation. */
public interface RetrievalTokenCounter {
    int count(String text);
    String fingerprint();
}
