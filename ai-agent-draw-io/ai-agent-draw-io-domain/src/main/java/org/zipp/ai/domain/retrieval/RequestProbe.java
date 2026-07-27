package org.zipp.ai.domain.retrieval;

import java.util.Objects;

public record RequestProbe(SourceProbe sources, CanvasProbe canvas) {
    public RequestProbe {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(canvas, "canvas");
    }
}
