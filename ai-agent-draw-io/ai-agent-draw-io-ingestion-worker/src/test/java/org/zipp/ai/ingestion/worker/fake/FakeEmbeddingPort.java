package org.zipp.ai.ingestion.worker.fake;

import org.zipp.ai.domain.retrieval.model.valobj.EmbeddingInputType;
import org.zipp.ai.domain.retrieval.port.EmbeddingPort;

import java.util.ArrayList;
import java.util.List;

public final class FakeEmbeddingPort implements EmbeddingPort {
    private final int dimension;

    public FakeEmbeddingPort(int dimension) {
        if (dimension < 1) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        this.dimension = dimension;
    }

    @Override
    public List<float[]> embed(List<String> texts, EmbeddingInputType inputType) {
        List<float[]> result = new ArrayList<>();
        for (String text : texts) {
            float[] vector = new float[dimension];
            int seed = 31 * text.hashCode() + inputType.ordinal();
            for (int i = 0; i < dimension; i++) {
                vector[i] = ((seed >>> (i % 16)) & 0xff) / 255.0f;
            }
            result.add(vector);
        }
        return result;
    }
}
