package org.zipp.ai.ingestion.worker.fake;

import org.zipp.ai.domain.ingestion.model.valobj.MaterialObject;
import org.zipp.ai.domain.ingestion.model.valobj.VisualAnalysisResult;
import org.zipp.ai.domain.ingestion.port.VisionAnalyzerPort;

public final class FakeVisionAnalyzer implements VisionAnalyzerPort {
    @Override
    public VisualAnalysisResult analyze(MaterialObject object, int pageNo, String regionRef) {
        return new VisualAnalysisResult(pageNo, regionRef, "diagram", "configured visual analysis", 0.9);
    }
}
