package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.aggregate.UploadSession;
import org.zipp.ai.domain.ingestion.model.valobj.BrowserPostPolicy;

public interface UploadPolicySignerPort {
    BrowserPostPolicy sign(UploadSession session);
}
