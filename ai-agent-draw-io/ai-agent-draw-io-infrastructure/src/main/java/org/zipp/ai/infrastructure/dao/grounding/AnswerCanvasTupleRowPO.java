package org.zipp.ai.infrastructure.dao.grounding;

import lombok.Data;

/** Locked authoritative canvas tuple used to prove that an answer did not mutate the canvas. */
@Data
public class AnswerCanvasTupleRowPO {
    private Long version;
    private String contentHash;
}
