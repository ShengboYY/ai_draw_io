package org.zipp.ai.domain.agent.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Loads a versioned case fixture without coupling the harness to production telemetry storage.
 */
public class EvalCaseLoader {

    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public EvalCaseDefinition load(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        EvalCaseDefinition result = mapper.readValue(input, EvalCaseDefinition.class);
        if (result == null || isBlank(result.getCaseId()) || isBlank(result.getDatasetVersion())) {
            throw new IllegalArgumentException("Eval case must include caseId and datasetVersion.");
        }
        if (result.getPrivacy() == null || !"synthetic".equals(result.getPrivacy().getClassification())) {
            throw new IllegalArgumentException("Eval case privacy.classification must be synthetic.");
        }
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
