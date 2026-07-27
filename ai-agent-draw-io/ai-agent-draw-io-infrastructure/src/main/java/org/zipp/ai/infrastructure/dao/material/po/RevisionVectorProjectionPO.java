package org.zipp.ai.infrastructure.dao.material.po;

import lombok.Data;

/** Persistence row binding one immutable revision plan to one vector generation. */
@Data
public class RevisionVectorProjectionPO {
    private String revisionId;
    private String indexGenerationId;
    private String tokenizerFingerprint;
    private String planFingerprint;
    private String projectionRole;
    private int expectedProjectionCount;
    private String state;
}
