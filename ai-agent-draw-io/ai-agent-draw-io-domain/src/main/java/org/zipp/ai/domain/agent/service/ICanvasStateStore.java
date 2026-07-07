package org.zipp.ai.domain.agent.service;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasState;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasStateSaveResult;

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

    default Optional<CanvasState> updateThumbnail(String userId, String diagramId, String thumbnailUrl) {
        return Optional.empty();
    }

    default boolean softDelete(String userId, String diagramId) {
        return false;
    }

    default List<CanvasState> importAnonymousWorkspace(String anonymousOwnerId, String targetOwnerId) {
        return Collections.emptyList();
    }

    default int deleteUserData(String userId, String anonymizedUserId) {
        return 0;
    }

    CanvasState save(CanvasState state);

    default CanvasStateSaveResult saveWithResult(CanvasState state) {
        return CanvasStateSaveResult.updated(save(state));
    }

}
