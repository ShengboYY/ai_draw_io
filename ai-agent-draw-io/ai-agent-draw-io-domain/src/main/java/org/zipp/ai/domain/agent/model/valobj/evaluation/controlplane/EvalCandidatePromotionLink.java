package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Restricted post-publication lineage; never serialize this value into a Case artifact or report. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCandidatePromotionLink {
    private String candidateId;
    private String workingCopyId;
    private String caseId;
    private String caseVersion;
    private Instant promotedAt;
    private Instant retentionExpiresAt;
}
