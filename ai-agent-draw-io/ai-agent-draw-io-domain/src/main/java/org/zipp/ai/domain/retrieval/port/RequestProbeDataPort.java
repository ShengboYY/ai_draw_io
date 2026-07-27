package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.RequestProbeCommand;
import org.zipp.ai.domain.retrieval.SourceProbe;

import java.util.List;
import java.util.Optional;

/** Read-only projection port. Implementations must not return document or canvas content. */
public interface RequestProbeDataPort {
    SourceProbe probeSources(RequestProbeCommand command);

    Optional<ServerCanvasFacts> loadCanvasFacts(CatalogOwner owner, String diagramId,
                                                List<String> selectedCellIds);

    record ServerCanvasFacts(int nodeCount, int edgeCount, long version, String contentHash,
                             int selectedCellCount, List<String> selectedKinds) {
        public ServerCanvasFacts {
            selectedKinds = List.copyOf(selectedKinds == null ? List.of() : selectedKinds);
        }
    }
}
