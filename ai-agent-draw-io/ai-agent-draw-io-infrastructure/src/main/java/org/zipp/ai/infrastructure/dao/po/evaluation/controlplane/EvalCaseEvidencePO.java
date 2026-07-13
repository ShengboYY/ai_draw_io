package org.zipp.ai.infrastructure.dao.po.evaluation.controlplane;

import lombok.Data;

import java.util.Date;

@Data
public class EvalCaseEvidencePO {
    private String id;
    private String workingCopyId;
    private Long workingCopyRevision;
    private String evidenceType;
    private String status;
    private String payloadJson;
    private String componentVersion;
    private Date createdAt;
}
