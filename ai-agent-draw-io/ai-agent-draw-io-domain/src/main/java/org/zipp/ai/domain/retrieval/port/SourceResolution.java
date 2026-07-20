package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;

public record SourceResolution(SourceMode mode, List<AuthorizedSource> sources,
                               List<String> unavailableExplicitVersionIds,
                               int pendingConversationUploadCount) {
    public SourceResolution(SourceMode mode, List<AuthorizedSource> sources,
                            List<String> unavailableExplicitVersionIds) {
        this(mode, sources, unavailableExplicitVersionIds, 0);
    }

    public SourceResolution {
        mode = mode == null ? SourceMode.AUTO : mode;
        sources = List.copyOf(sources == null ? List.of() : sources);
        unavailableExplicitVersionIds = List.copyOf(
                unavailableExplicitVersionIds == null ? List.of() : unavailableExplicitVersionIds);
        pendingConversationUploadCount = Math.max(0, pendingConversationUploadCount);
    }

}
