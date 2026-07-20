package org.zipp.ai.domain.retrieval.internal;

import org.zipp.ai.domain.retrieval.CanvasProbe;
import org.zipp.ai.domain.retrieval.port.ServerCanvasPort;

/** Package-private fail-closed loader; it has no client XML fallback. */
final class ServerCanvasSnapshotLoader {
    private final ServerCanvasPort canvases;

    ServerCanvasSnapshotLoader(ServerCanvasPort canvases) {
        this.canvases = canvases;
    }

    LoadResult load(org.zipp.ai.domain.material.model.valobj.CatalogOwner owner,
                    String diagramId, CanvasProbe expected) {
        var snapshot = canvases.load(owner, diagramId);
        if (snapshot.isEmpty()) return new LoadResult.Unavailable("SERVER_CANVAS_UNAVAILABLE");
        ServerCanvasPort.ServerCanvasSnapshot actual = snapshot.get();
        if (expected.serverCanvasVersion() != null && expected.serverCanvasVersion() != actual.version()) {
            return new LoadResult.Changed(expected.serverCanvasVersion(), actual.version());
        }
        if (!expected.contentHash().isBlank() && !expected.contentHash().equals(actual.contentHash())) {
            return new LoadResult.Changed(expected.serverCanvasVersion(), actual.version());
        }
        return new LoadResult.Ready(actual);
    }

    sealed interface LoadResult permits LoadResult.Ready, LoadResult.Changed, LoadResult.Unavailable {
        record Ready(ServerCanvasPort.ServerCanvasSnapshot snapshot) implements LoadResult { }
        record Changed(Long expectedVersion, Long actualVersion) implements LoadResult { }
        record Unavailable(String errorCode) implements LoadResult { }
    }
}
