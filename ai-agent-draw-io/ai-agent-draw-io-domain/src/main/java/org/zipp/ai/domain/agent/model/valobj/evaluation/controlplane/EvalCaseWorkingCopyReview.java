package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Immutable human review decision for a qualified working copy. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseWorkingCopyReview {
    private String id;
    private String workingCopyId;
    private Long workingCopyRevision;
    private String reviewerUserId;
    private String decision;
    private String reason;
    private Instant createdAt;
}
