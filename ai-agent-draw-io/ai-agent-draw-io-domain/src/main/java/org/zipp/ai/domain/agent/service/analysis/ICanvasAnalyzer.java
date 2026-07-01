package org.zipp.ai.domain.agent.service.analysis;

import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;

public interface ICanvasAnalyzer {

    CanvasAnalysis analyze(String mxGraphModelXml, String diagramType);

}
