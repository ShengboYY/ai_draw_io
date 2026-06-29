package org.zipp.ai.domain.agent.service;

import org.zipp.ai.domain.agent.model.valobj.canvas.DrawioCanvasSnapshot;

public interface IDrawioCanvasSnapshotService {

    DrawioCanvasSnapshot fromMessage(String message, String diagramType);

    DrawioCanvasSnapshot fromXml(String xml, String diagramType);

}
