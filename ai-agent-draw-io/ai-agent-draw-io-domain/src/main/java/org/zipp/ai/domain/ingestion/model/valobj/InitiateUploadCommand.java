package org.zipp.ai.domain.ingestion.model.valobj;

import org.zipp.ai.domain.account.model.valobj.OwnerType;

public record InitiateUploadCommand(OwnerType ownerType,
                                    String ownerKey,
                                    String idempotencyKey,
                                    String displayName,
                                    String declaredMediaType,
                                    long byteSize,
                                    String sha256,
                                    UploadTarget target,
                                    String contextDiagramId,
                                    String newVersionOfMaterialId,
                                    String ipRateKey,
                                    int batchFileCount) {
}
