package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.MaterialObject;
import org.zipp.ai.domain.ingestion.model.valobj.OcrResult;

public interface OcrEnginePort {
    OcrResult recognize(MaterialObject object, int pageNo);
}
