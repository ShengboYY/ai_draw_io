package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Deterministic schema and privacy validation outcome. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseValidationResult {
    private boolean passed;
    @Builder.Default
    private List<String> evidence = new ArrayList<>();
    private EvalCaseWorkingCopy workingCopy;
}
