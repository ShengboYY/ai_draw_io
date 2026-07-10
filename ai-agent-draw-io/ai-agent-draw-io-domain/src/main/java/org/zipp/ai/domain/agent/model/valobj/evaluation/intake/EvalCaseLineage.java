package org.zipp.ai.domain.agent.model.valobj.evaluation.intake;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/** Long-lived non-sensitive publication proof. Deliberately contains no source run or candidate reference. */
@Data
@Builder
public class EvalCaseLineage {
    private String promotionId;
    private String caseId;
    private String datasetVersion;
    private String reviewer;
    private Instant approvedAt;
    private String sanitizerVersion;
    private String origin;
}
