package org.zipp.ai.domain.ingestion.port;

import org.zipp.ai.domain.ingestion.model.valobj.OriginalPromotionWork;
import org.zipp.ai.domain.ingestion.model.valobj.PromotedOriginal;

public interface OriginalPromotionPort {
    PromotedOriginal promote(OriginalPromotionWork work);

    default void discard(PromotedOriginal promotedOriginal) {
        // Infrastructure implementations remove an uncommitted immutable destination version when possible.
    }
}
