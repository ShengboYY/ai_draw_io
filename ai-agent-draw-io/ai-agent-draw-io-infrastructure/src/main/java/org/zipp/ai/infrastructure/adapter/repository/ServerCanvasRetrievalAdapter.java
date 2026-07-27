package org.zipp.ai.infrastructure.adapter.repository;

import org.zipp.ai.domain.agent.service.ICanvasStateStore;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.port.ServerCanvasPort;

import java.util.Objects;
import java.util.Optional;

/** Evidence target resolution reads only the owner-fenced persisted canvas. */
public final class ServerCanvasRetrievalAdapter implements ServerCanvasPort {
    private final ICanvasStateStore canvases;

    public ServerCanvasRetrievalAdapter(ICanvasStateStore canvases) {
        this.canvases = Objects.requireNonNull(canvases, "canvases");
    }

    @Override
    public Optional<ServerCanvasSnapshot> load(CatalogOwner owner, String diagramId) {
        return canvases.find(owner.ownerKey(), diagramId).map(state -> new ServerCanvasSnapshot(
                state.getVersion() == null ? 0 : state.getVersion(), state.getContentHash(), state.getCurrentXml()));
    }
}
