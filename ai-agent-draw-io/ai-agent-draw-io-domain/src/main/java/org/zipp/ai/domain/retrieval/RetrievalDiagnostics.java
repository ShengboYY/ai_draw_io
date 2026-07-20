package org.zipp.ai.domain.retrieval;

import java.util.List;

public record RetrievalDiagnostics(RetrievalRoute route, List<String> codes) {
    public RetrievalDiagnostics {
        route = route == null ? RetrievalRoute.NONE : route;
        codes = List.copyOf(codes == null ? List.of() : codes);
    }
}
