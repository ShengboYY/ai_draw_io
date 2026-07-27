package org.zipp.ai.domain.citation.answer;

import org.zipp.ai.domain.citation.model.valobj.SupportAtom;
import java.util.List;

/** Untrusted structured claim proposed by the answer model. */
public record AnswerClaim(String claimKey, String statementText, List<String> citationKeys,
                          AnswerSupportType supportType, List<SupportAtom> supportAtoms) {
    public AnswerClaim {
        citationKeys = List.copyOf(citationKeys == null ? List.of() : citationKeys);
        supportAtoms = List.copyOf(supportAtoms == null ? List.of() : supportAtoms);
    }
}
