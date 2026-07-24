package org.zipp.ai.domain.multimodal;

import java.util.List;

/** Typed result of checking a projected Draw.io candidate against its observed graph. */
record DirectDiagramProjectionVerification(List<Issue> issues) {
    DirectDiagramProjectionVerification {
        issues = List.copyOf(issues == null ? List.of() : issues);
    }

    boolean verified() {
        return issues.isEmpty();
    }

    enum Issue {
        MALFORMED_XML,
        CELL_MANIFEST_MISMATCH,
        PROJECTION_MISMATCH
    }
}
