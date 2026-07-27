package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;

public record AuthorizedSourceSet(CatalogOwner owner, SourceMode mode, List<AuthorizedSource> sources) {
    public AuthorizedSourceSet {
        java.util.Objects.requireNonNull(owner, "owner");
        mode = mode == null ? SourceMode.AUTO : mode;
        sources = List.copyOf(sources == null ? List.of() : sources);
    }
}
