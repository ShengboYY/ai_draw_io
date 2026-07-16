package org.zipp.ai.domain.agent.service.analysis;

import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysisRequest;
import java.util.Locale;

public interface ICanvasAnalyzer {

    /** Legacy SAM retained during migration so existing implementations and lambdas still compile. */
    CanvasAnalysis analyze(String mxGraphModelXml, String diagramType);

    /**
     * Compatibility for legacy implementers. Production analyzers override this typed entry point;
     * remove the default after implementations migrate in Phase 7.
     */
    default CanvasAnalysis analyze(CanvasAnalysisRequest request) {
        return analyze(request.mxGraphModelXml(), request.diagramType().name().toLowerCase(Locale.ROOT));
    }

}
