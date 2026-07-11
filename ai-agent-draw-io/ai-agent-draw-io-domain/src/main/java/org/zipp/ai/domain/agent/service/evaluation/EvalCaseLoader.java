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

    private static final String SUPPORTED_FIXTURE_VERSION = "fixture-v1";
    private static final String SUPPORTED_XML_CONTRACT_VERSION = "drawio-v1";
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public EvalCaseDefinition load(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        EvalCaseDefinition result = mapper.readValue(input, EvalCaseDefinition.class);
        if (result == null || isBlank(result.getCaseId()) || isBlank(result.getCaseVersion())
                || isBlank(result.getDatasetVersion())) {
            throw new IllegalArgumentException("Eval case must include caseId, caseVersion, and datasetVersion.");
        }
        if (result.getPrivacy() == null || !"synthetic".equals(result.getPrivacy().getClassification())) {
            throw new IllegalArgumentException("Eval case privacy.classification must be synthetic.");
        }
        if (isBlank(result.getOrigin()) || isBlank(result.getRisk()) || result.getExpected() == null
                || result.getInput() == null) {
            throw new IllegalArgumentException("Eval case must include origin, risk, input, and expected.");
        }
        if (!SUPPORTED_FIXTURE_VERSION.equals(result.getFixtureVersion())) {
            throw new IllegalArgumentException("Unsupported fixtureVersion: " + result.getFixtureVersion());
        }
        if (!SUPPORTED_XML_CONTRACT_VERSION.equals(result.getXmlContractVersion())) {
            throw new IllegalArgumentException("Unsupported xmlContractVersion: " + result.getXmlContractVersion());
        }
        if (result.getExpected().getGraph() != null
                && isBlank(result.getExpected().getGraph().getAliasMapVersion())) {
            throw new IllegalArgumentException("Graph assertions must include aliasMapVersion.");
        }
        if ("trace-derived-synthetic".equals(result.getOrigin())
                && (result.getProvenance() == null || isBlank(result.getProvenance().getReviewer()))) {
            throw new IllegalArgumentException("Trace-derived case must include reviewed provenance.");
        }
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
