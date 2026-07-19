package org.zipp.ai.ingestion.worker.fake;

import org.zipp.ai.domain.ingestion.model.valobj.OcrResult;
import org.zipp.ai.domain.ingestion.model.valobj.OcrWord;
import org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox;
import org.zipp.ai.domain.ingestion.port.OcrEnginePort;

public final class FakeOcrEngine implements OcrEnginePort {
    @Override
    public OcrResult recognize(java.nio.file.Path pageImage, int pageNo) {
        return new OcrResult(pageNo, "ocr-page-" + pageNo, 0.95,
                java.util.List.of(new OcrWord("ocr-page-" + pageNo,
                        new NormalizedBoundingBox(0.1, 0.1, 0.9, 0.2), 0.95, "line:1")));
    }
}
