package org.zipp.ai.domain.agent.model.valobj.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One completed deterministic or live evaluation episode with its final canvas artifact.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalExecution {

    private EvalCaseDefinition evalCase;
    private EvalTrace trace;
    private String finalCanvasXml;
    private String gitSha;
    private String executionProfileHash;
    private String promptConfigHash;
    private String skillCatalogHash;
    private String toolPolicyVersion;
}
