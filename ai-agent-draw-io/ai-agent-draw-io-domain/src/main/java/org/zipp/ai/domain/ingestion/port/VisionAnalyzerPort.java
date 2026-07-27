package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.MaterialObject;
import org.zipp.ai.domain.ingestion.model.valobj.VisualAnalysisResult;

public interface VisionAnalyzerPort {
    VisualAnalysisResult analyze(MaterialObject object, int pageNo, String regionRef);
}
