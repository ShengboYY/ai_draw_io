package org.zipp.ai.domain.agent.service;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public interface ICanvasStateStore {

    Optional<CanvasState> find(String userId, String diagramId);

    default List<CanvasState> list(String userId) {
        return Collections.emptyList();
    }

    default Optional<CanvasState> rename(String userId, String diagramId, String title) {
        return Optional.empty();
    }

    default boolean softDelete(String userId, String diagramId) {
        return false;
    }

    CanvasState save(CanvasState state);

}
