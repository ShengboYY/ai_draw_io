package org.zipp.ai.infrastructure.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.zipp.ai.domain.operations.RagEvaluationCase;

/** Loads source-controlled synthetic RAG fixtures without coupling them to production traces. */
public final class RagEvaluationCaseLoader {
    private final ObjectMapper mapper = new ObjectMapper();

    public RagEvaluationCase load(InputStream input) throws IOException {
        try {
            return mapper.readValue(Objects.requireNonNull(input, "input"), RagEvaluationCase.class);
        } catch (JsonMappingException exception) {
            if (exception.getCause() instanceof IllegalArgumentException invalidCase) throw invalidCase;
            throw exception;
        }
    }
}
