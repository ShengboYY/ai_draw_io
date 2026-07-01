package org.zipp.ai.domain.agent.service;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;

import java.util.Optional;

public interface ICanvasStateStore {

    Optional<CanvasState> find(String userId, String diagramId);

    CanvasState save(CanvasState state);

}
