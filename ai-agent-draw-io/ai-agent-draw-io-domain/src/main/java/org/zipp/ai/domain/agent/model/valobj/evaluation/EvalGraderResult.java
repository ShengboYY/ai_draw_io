package org.zipp.ai.domain.agent.model.valobj.evaluation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializable grader result so reports explain a failure without retaining sensitive artifacts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalGraderResult {

    private String graderName;
    private String graderVersion;
    private boolean passed;
    /** Highest deterministic issue severity represented by this grader result. */
    private String severity;

    @Builder.Default
    private List<String> evidence = new ArrayList<>();
}
