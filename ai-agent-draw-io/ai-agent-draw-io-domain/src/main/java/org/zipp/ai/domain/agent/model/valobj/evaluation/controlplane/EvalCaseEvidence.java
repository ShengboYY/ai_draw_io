package org.zipp.ai.domain.agent.model.valobj.evaluation.controlplane;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Immutable validation or dry-run evidence tied to one working-copy revision. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalCaseEvidence {
    private String id;
    private String workingCopyId;
    private Long workingCopyRevision;
    private EvalCaseEvidenceType type;
    private String status;
    private String payloadJson;
    private String componentVersion;
    private Instant createdAt;
}
