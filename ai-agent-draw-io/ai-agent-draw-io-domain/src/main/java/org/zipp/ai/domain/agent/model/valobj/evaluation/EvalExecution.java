package org.zipp.ai.domain.agent.model.valobj.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

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
    private String initialCanvasXml;
    private String finalCanvasXml;
    private String gitSha;
    private String executionProfileHash;
    private String promptConfigHash;
    private String skillCatalogHash;
    private String toolPolicyVersion;

    @Builder.Default
    private List<TurnExecution> turns = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TurnExecution {
        private int index;
        private String user;
        private EvalTrace trace;
        private String beforeCanvasXml;
        private String afterCanvasXml;
    }
}
