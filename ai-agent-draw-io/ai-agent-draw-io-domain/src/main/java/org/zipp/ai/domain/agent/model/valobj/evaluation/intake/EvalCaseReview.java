package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Review metadata only; the synthetic case itself is authored separately in the eval dataset. */
@Data
@Builder
public class EvalCaseReview {
    private String id;
    private String candidateId;
    private String reviewer;
    private String decision;
    private String reason;
    private Instant reviewedAt;
}
