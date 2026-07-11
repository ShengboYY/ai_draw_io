package org.zipp.ai.domain.agent.model.valobj.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate Phase 1 result with the versions required to explain historical regressions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalHarnessResult {

    private String caseId;
    private String caseVersion;
    private String gitSha;
    private String executionProfileHash;
    private String promptConfigHash;
    private String skillCatalogHash;
    private String toolPolicyVersion;
    private long latencyMs;
    private Status status;
    private String errorClass;
    private String errorMessage;
    private boolean passed;

    @Builder.Default
    private List<EvalGraderResult> graders = new ArrayList<>();

    public enum Status {
        PASS,
        FAIL,
        ERROR,
        UNAVAILABLE
    }
}
