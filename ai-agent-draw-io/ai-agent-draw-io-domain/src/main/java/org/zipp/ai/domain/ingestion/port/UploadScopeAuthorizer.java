package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.ingestion.model.valobj.UploadTarget;

public interface UploadScopeAuthorizer {
    boolean canUpload(OwnerType ownerType, String ownerKey, UploadTarget target, String contextDiagramId,
                      String newVersionOfMaterialId);
}
