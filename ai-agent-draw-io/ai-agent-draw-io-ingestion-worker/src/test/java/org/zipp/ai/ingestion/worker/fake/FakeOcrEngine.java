package org.zipp.ai.ingestion.worker.fake;

import org.zipp.ai.domain.ingestion.model.valobj.MaterialObject;
import org.zipp.ai.domain.ingestion.model.valobj.OcrResult;
import org.zipp.ai.domain.ingestion.port.OcrEnginePort;

public final class FakeOcrEngine implements OcrEnginePort {
    @Override
    public OcrResult recognize(MaterialObject object, int pageNo) {
        return new OcrResult(pageNo, "ocr-page-" + pageNo, 0.95);
    }
}
