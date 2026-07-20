package org.zipp.ai.domain.citation.model.valobj;

import java.util.List;

/** Durable read projection for one grounded conversation claim and its displayed sources. */
public record AnswerCitationView(String messageId, String claimKey, String supportType,
                                 List<AnswerSourceView> sources) {
    public AnswerCitationView {
        sources = List.copyOf(sources == null ? List.of() : sources);
    }

    public record AnswerSourceView(String citationKey, String sourceLabel, Integer pageNumber,
                                   String modality, String origin) { }
}
