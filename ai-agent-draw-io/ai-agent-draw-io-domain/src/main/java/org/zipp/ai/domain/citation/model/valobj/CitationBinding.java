package org.zipp.ai.domain.citation.model.valobj;

import java.util.List;

/** Model-proposed binding; every field is revalidated against final XML and the run whitelist. */
public record CitationBinding(String cellId, String statementKey, StatementKind statementKind,
                              String statementText, String sourceCellId, String targetCellId,
                              List<String> citationKeys, List<SupportAtom> supportAtoms,
                              SupportType supportType) {
    public CitationBinding {
        cellId = required(cellId, "cellId");
        statementKey = required(statementKey, "statementKey");
        statementText = required(statementText, "statementText");
        if (statementKind == null) throw new IllegalArgumentException("statementKind is required");
        citationKeys = List.copyOf(citationKeys == null ? List.of() : citationKeys.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList());
        supportAtoms = List.copyOf(supportAtoms == null ? List.of() : supportAtoms);
        if (supportType == null) throw new IllegalArgumentException("supportType is required");
        sourceCellId = text(sourceCellId);
        targetCellId = text(targetCellId);
    }

    private static String required(String value, String field) {
        String normalized = text(value);
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
