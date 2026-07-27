package org.zipp.ai.domain.citation.answer;

import java.util.List;

/** Strict model contract. Free-form answer Markdown is deliberately not accepted. */
public record AnswerProposal(List<AnswerClaim> claims, List<AnswerGap> gaps,
                             List<AnswerConflict> conflicts) {
    public AnswerProposal {
        claims = List.copyOf(claims == null ? List.of() : claims);
        gaps = List.copyOf(gaps == null ? List.of() : gaps);
        conflicts = List.copyOf(conflicts == null ? List.of() : conflicts);
    }
}
