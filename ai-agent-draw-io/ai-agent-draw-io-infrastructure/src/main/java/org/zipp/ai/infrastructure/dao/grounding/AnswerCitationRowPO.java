package org.zipp.ai.infrastructure.dao.grounding;

import lombok.Data;

/** Flat MyBatis row grouped by the citation read adapter into answer claims. */
@Data
public class AnswerCitationRowPO {
    private String citationId;
    private String messageId;
    private String claimKey;
    private String supportType;
    private String citationKey;
    private String sourceLabel;
    private Integer pageNumber;
    private String modality;
    private String sourceOrigin;
}
