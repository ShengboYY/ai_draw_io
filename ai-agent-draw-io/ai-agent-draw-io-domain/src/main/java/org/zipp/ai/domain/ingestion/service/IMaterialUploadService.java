package org.zipp.ai.domain.ingestion.service;

import org.zipp.ai.domain.ingestion.model.valobj.CompleteUploadCommand;
import org.zipp.ai.domain.ingestion.model.valobj.InitiateUploadCommand;
import org.zipp.ai.domain.ingestion.model.valobj.InitiateUploadResult;
import org.zipp.ai.domain.ingestion.model.valobj.UploadSessionStatus;

public interface IMaterialUploadService {
    InitiateUploadResult initiate(InitiateUploadCommand command);
    UploadSessionStatus complete(CompleteUploadCommand command);
    UploadSessionStatus status(CompleteUploadCommand command);
}
