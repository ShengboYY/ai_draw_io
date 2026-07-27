package org.zipp.ai.infrastructure.adapter.vector;

import org.zipp.ai.domain.retrieval.model.valobj.EmbeddingInputType;
import org.zipp.ai.domain.retrieval.port.EmbeddingPort;

import java.util.List;
import java.util.Objects;

public final class PineconeEmbeddingAdapter implements EmbeddingPort {
    private final PineconeVectorClient client;

    public PineconeEmbeddingAdapter(PineconeVectorClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public List<float[]> embed(List<String> texts, EmbeddingInputType inputType) {
        return client.embed(texts, Objects.requireNonNull(inputType, "inputType").name().toLowerCase(
                java.util.Locale.ROOT));
    }
}
