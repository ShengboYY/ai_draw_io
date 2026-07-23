package org.zipp.ai.domain.retrieval;

import org.zipp.ai.domain.material.model.valobj.MaterialScopeType;
import org.zipp.ai.domain.retrieval.port.SourceResolution;

import java.util.List;

/** Immutable, content-free source authorization snapshot shared by all consumers of one request. */
public record ResolvedSourceSet(SourceMode mode, List<ResolvedSource> sources,
                                int processingSourceCount, int unavailableSourceCount,
                                boolean resolutionFailed) {
    public ResolvedSourceSet {
        mode = mode == null ? SourceMode.AUTO : mode;
        sources = List.copyOf(sources == null ? List.of() : sources);
        processingSourceCount = Math.max(0, processingSourceCount);
        unavailableSourceCount = Math.max(0, unavailableSourceCount);
    }

    public ResolvedSourceSet(SourceMode mode, List<ResolvedSource> sources,
                             int processingSourceCount, int unavailableSourceCount) {
        this(mode, sources, processingSourceCount, unavailableSourceCount, false);
    }

    public static ResolvedSourceSet empty(SourceMode mode) {
        return new ResolvedSourceSet(mode, List.of(), 0, 0);
    }

    /** Represents an infrastructure/conflict failure that must never degrade to legacy drawing. */
    public static ResolvedSourceSet failed(SourceMode mode) {
        return new ResolvedSourceSet(mode, List.of(), 0, 0, true);
    }

    public SourceProbe toProbe() {
        List<ResolvedSource> declared = sources.stream().filter(ResolvedSource::declared).toList();
        return new SourceProbe(declared.size(),
                declared.stream().map(ResolvedSource::kind).distinct().toList(),
                declared.stream().map(ResolvedSource::state).distinct().toList(),
                processingSourceCount,
                hasReadyScope(MaterialScopeType.DIAGRAM),
                hasReadyScope(MaterialScopeType.CHARTBOOK),
                hasReadyScope(MaterialScopeType.LIBRARY),
                sources.stream().anyMatch(ResolvedSource::pinned),
                sources.stream().anyMatch(ResolvedSource::hasVisual),
                (int) declared.stream().filter(source -> "PARTIAL_READY".equals(source.state())).count(),
                mode);
    }

    public SourceResolution toSourceResolution() {
        return new SourceResolution(mode, sources.stream().map(ResolvedSource::authorizedSource).toList(),
                unavailableSourceCount, processingSourceCount);
    }

    public List<String> declaredVersionIds() {
        return sources.stream().filter(ResolvedSource::declared).map(ResolvedSource::versionId).distinct().toList();
    }

    private boolean hasReadyScope(MaterialScopeType scopeType) {
        return sources.stream().anyMatch(source -> source.scopeType() == scopeType && source.ready());
    }
}
