package org.zipp.ai.domain.citation.model.valobj;

import java.util.List;

public record CitationGuardResult(boolean accepted, List<CitationBinding> bindings, List<String> errors) {
    public CitationGuardResult {
        bindings = List.copyOf(bindings == null ? List.of() : bindings);
        errors = List.copyOf(errors == null ? List.of() : errors);
    }

    public static CitationGuardResult accepted(List<CitationBinding> bindings) {
        return new CitationGuardResult(true, bindings, List.of());
    }

    public static CitationGuardResult rejected(List<String> errors) {
        return new CitationGuardResult(false, List.of(), errors);
    }
}
