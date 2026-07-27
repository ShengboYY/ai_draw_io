package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.retrieval.SourceMode;

import java.util.List;

public record SourceResolution(SourceMode mode, List<AuthorizedSource> sources,
                               List<String> unavailableExplicitVersionIds,
                               int unavailableExplicitSourceCount,
                               int pendingConversationUploadCount) {
    public SourceResolution(SourceMode mode, List<AuthorizedSource> sources,
                            List<String> unavailableExplicitVersionIds) {
        this(mode, sources, unavailableExplicitVersionIds, 0, 0);
    }

    public SourceResolution(SourceMode mode, List<AuthorizedSource> sources,
                            List<String> unavailableExplicitVersionIds,
                            int pendingConversationUploadCount) {
        this(mode, sources, unavailableExplicitVersionIds, unavailableExplicitVersionIds == null
                ? 0 : unavailableExplicitVersionIds.size(), pendingConversationUploadCount);
    }

    public SourceResolution(SourceMode mode, List<AuthorizedSource> sources,
                            int unavailableExplicitSourceCount,
                            int pendingConversationUploadCount) {
        this(mode, sources, List.of(), unavailableExplicitSourceCount, pendingConversationUploadCount);
    }

    public SourceResolution {
        mode = mode == null ? SourceMode.AUTO : mode;
        sources = List.copyOf(sources == null ? List.of() : sources);
        unavailableExplicitVersionIds = List.copyOf(
                unavailableExplicitVersionIds == null ? List.of() : unavailableExplicitVersionIds);
        unavailableExplicitSourceCount = Math.max(
                unavailableExplicitSourceCount, unavailableExplicitVersionIds.size());
        pendingConversationUploadCount = Math.max(0, pendingConversationUploadCount);
    }

}
