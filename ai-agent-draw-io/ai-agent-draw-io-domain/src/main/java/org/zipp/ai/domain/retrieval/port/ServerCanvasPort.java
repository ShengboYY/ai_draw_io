package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.util.Optional;

public interface ServerCanvasPort {
    Optional<ServerCanvasSnapshot> load(CatalogOwner owner, String diagramId);

    record ServerCanvasSnapshot(long version, String contentHash, String xml) { }
}
