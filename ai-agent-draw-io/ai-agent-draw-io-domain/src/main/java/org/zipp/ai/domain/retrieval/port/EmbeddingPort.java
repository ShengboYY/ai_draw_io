package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.retrieval.model.valobj.EmbeddingInputType;

import java.util.List;

public interface EmbeddingPort {
    List<float[]> embed(List<String> texts, EmbeddingInputType inputType);
}
