package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.OcrResult;

import java.nio.file.Path;

public interface OcrEnginePort {
    OcrResult recognize(Path pageImage, int pageNo);
}
