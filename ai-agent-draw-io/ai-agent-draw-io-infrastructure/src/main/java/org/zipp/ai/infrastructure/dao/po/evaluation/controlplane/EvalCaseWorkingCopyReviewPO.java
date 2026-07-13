package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;

import java.util.Date;

@Data
public class EvalCaseWorkingCopyReviewPO {
    private String id;
    private String workingCopyId;
    private Long workingCopyRevision;
    private String reviewerUserId;
    private String decision;
    private String reason;
    private Date createdAt;
}
