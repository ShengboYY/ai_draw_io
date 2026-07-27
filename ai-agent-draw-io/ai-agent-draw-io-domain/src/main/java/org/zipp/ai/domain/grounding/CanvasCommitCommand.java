package org.zipp.ai.domain.grounding;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationCommand;
import org.zipp.ai.domain.citation.model.valobj.CitationBinding;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record CanvasCommitCommand(CanvasMutationCommand mutation, String requestId, String runId,
                                  EvidenceAccessContext evidenceAccess, List<CitationBinding> bindings,
                                  boolean manifestValid, boolean strict,
                                  Set<String> immutableCellIds) {
    public CanvasCommitCommand {
        Objects.requireNonNull(mutation, "mutation");
        Objects.requireNonNull(evidenceAccess, "evidenceAccess");
        requestId = required(requestId, "requestId");
        runId = required(runId, "runId");
        bindings = List.copyOf(bindings == null ? List.of() : bindings);
        immutableCellIds = Set.copyOf(immutableCellIds == null ? Set.of() : immutableCellIds);
    }

    /** Compatibility constructor for grounded tasks without immutable direct-image cells. */
    public CanvasCommitCommand(CanvasMutationCommand mutation, String requestId, String runId,
                               EvidenceAccessContext evidenceAccess, List<CitationBinding> bindings,
                               boolean manifestValid, boolean strict) {
        this(mutation, requestId, runId, evidenceAccess, bindings,
                manifestValid, strict, Set.of());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
