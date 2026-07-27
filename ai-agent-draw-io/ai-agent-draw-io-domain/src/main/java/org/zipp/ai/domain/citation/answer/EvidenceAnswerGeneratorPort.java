package org.zipp.ai.domain.citation.answer;

import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import java.util.List;

/** No-tool model boundary. Inputs are display evidence, never storage capabilities. */
public interface EvidenceAnswerGeneratorPort {
    AnswerProposal generate(GenerationCommand command);

    record GenerationCommand(String runId, String question, String targetContext, String conversationContext,
                             List<EvidenceBundleItem> evidence) {
        public GenerationCommand {
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }
    }
}
