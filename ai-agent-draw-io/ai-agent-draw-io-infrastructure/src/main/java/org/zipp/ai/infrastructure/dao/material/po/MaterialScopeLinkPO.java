package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class MaterialScopeLinkPO {
    private String id;
    private String materialId;
    private String scopeType;
    private String scopeKey;
    private String createdBy;
}
