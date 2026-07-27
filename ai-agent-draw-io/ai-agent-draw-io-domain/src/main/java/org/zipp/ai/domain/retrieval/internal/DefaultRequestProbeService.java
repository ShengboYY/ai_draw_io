package org.zipp.ai.domain.retrieval.internal;

import org.zipp.ai.domain.retrieval.CanvasProbe;
import org.zipp.ai.domain.retrieval.RequestProbe;
import org.zipp.ai.domain.retrieval.RequestProbeCommand;
import org.zipp.ai.domain.retrieval.RequestProbeService;
import org.zipp.ai.domain.retrieval.SourceProbe;
import org.zipp.ai.domain.retrieval.port.RequestProbeDataPort;

import java.util.Objects;

/** Combines authorization-safe source and canvas projections for Intent Router V2. */
public final class DefaultRequestProbeService implements RequestProbeService {
    private final RequestProbeDataPort data;

    public DefaultRequestProbeService(RequestProbeDataPort data) {
        this.data = Objects.requireNonNull(data, "data");
    }

    @Override
    public RequestProbe probe(RequestProbeCommand command) {
        Objects.requireNonNull(command, "command");
        SourceProbe sources = command.resolvedSources() == null
                ? data.probeSources(command)
                : command.resolvedSources().toProbe();
        CanvasProbe canvas = data.loadCanvasFacts(command.owner(), command.diagramId(), command.selectedCellIds())
                .map(facts -> new CanvasProbe(facts.nodeCount() + facts.edgeCount() > 0,
                        facts.nodeCount(), facts.edgeCount(), facts.version(), facts.contentHash(),
                        facts.selectedCellCount(), facts.selectedKinds(),
                        selectionMismatch(command, facts), false))
                .orElseGet(CanvasProbe::unavailableProbe);
        return new RequestProbe(sources, canvas);
    }

    private boolean selectionMismatch(RequestProbeCommand command, RequestProbeDataPort.ServerCanvasFacts facts) {
        if (command.selectedCellIds().isEmpty()) return false;
        return command.selectionCanvasVersion() == null
                || command.selectionCanvasVersion() != facts.version()
                || command.selectionContentHash().isBlank()
                || !command.selectionContentHash().equals(facts.contentHash());
    }
}
