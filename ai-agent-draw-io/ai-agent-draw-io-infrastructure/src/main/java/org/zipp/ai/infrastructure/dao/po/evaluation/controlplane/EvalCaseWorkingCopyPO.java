package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;

import java.util.Date;

/** Database projection; definitionJson is the canonical EvalCaseDefinition. */
@Data
public class EvalCaseWorkingCopyPO {
    private String id;
    private String caseId;
    private String caseVersion;
    private String sourceType;
    private String candidateId;
    private String status;
    private String ownerUserId;
    private Long revision;
    private String definitionJson;
    private Date createdAt;
    private Date updatedAt;
}
