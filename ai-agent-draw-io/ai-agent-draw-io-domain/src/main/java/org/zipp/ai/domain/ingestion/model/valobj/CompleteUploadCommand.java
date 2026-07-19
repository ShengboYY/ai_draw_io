package org.zipp.ai.domain.ingestion.model.valobj;

import org.zipp.ai.domain.account.model.valobj.OwnerType;

public record CompleteUploadCommand(OwnerType ownerType, String ownerKey, String uploadId) {
}
