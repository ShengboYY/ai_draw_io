package org.zipp.ai.infrastructure.dao.po.evaluation;

import lombok.Data;

import java.util.Date;

@Data
public class EvalCaseDraftPO {
    private String id;
    private String candidateId;
    private String draftJson;
    private String sanitizerVersion;
    private String modelVersion;
    private Date createdAt;
}
