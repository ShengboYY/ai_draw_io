package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

@Data
public class MaterialObjectVersionPO {
    private String bucket;
    private String objectKey;
    private String objectVersionId;
}
