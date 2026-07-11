package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Non-sensitive Evaluation feedback; it cannot reference production runs or users. */
@Data
@Builder
public class EvalCaseHealthRecord {
    private String caseId;
    private String caseVersion;
    private Boolean baselineReproduced;
    private String healthStatus;
    private String summary;
    private Instant updatedAt;
}
