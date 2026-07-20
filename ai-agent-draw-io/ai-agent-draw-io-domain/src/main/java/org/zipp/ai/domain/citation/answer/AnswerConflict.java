package org.zipp.ai.domain.citation.answer;

import java.util.List;

/** Conflicting citation groups are warned about, never merged into a factual claim. */
public record AnswerConflict(String conflictKey, String facetKey,
                             List<List<String>> citationGroups, String reasonCode) {
    public AnswerConflict {
        citationGroups = citationGroups == null ? List.of() : citationGroups.stream()
                .map(group -> List.copyOf(group == null ? List.of() : group)).toList();
    }
}
