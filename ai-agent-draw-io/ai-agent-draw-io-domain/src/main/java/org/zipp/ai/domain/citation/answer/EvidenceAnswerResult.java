package org.zipp.ai.domain.citation.answer;

import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import java.util.List;

/** Safe result produced only after the answer transaction commits. */
public record EvidenceAnswerResult(boolean committed, String messageId, String content,
                                   List<AnswerClaim> claims, List<AnswerGap> gaps,
                                   List<AnswerConflict> conflicts,
                                   List<EvidenceBundleItem> sources, List<String> errors) {
    public EvidenceAnswerResult {
        claims = List.copyOf(claims == null ? List.of() : claims);
        gaps = List.copyOf(gaps == null ? List.of() : gaps);
        conflicts = List.copyOf(conflicts == null ? List.of() : conflicts);
        sources = List.copyOf(sources == null ? List.of() : sources);
        errors = List.copyOf(errors == null ? List.of() : errors);
    }

    public static EvidenceAnswerResult rejected(String messageId, List<String> errors) {
        return new EvidenceAnswerResult(false, messageId, "", List.of(), List.of(),
                List.of(), List.of(), errors);
    }
}
