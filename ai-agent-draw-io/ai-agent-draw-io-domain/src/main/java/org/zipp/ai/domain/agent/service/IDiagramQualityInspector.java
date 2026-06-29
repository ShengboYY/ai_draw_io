package org.zipp.ai.domain.agent.service;

import org.zipp.ai.domain.agent.model.valobj.quality.DiagramQualityReport;

public interface IDiagramQualityInspector {

    DiagramQualityReport inspect(String message, String diagramType);

}
