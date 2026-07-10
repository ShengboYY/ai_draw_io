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
    private boolean passed;

    @Builder.Default
    private List<EvalGraderResult> graders = new ArrayList<>();
}
