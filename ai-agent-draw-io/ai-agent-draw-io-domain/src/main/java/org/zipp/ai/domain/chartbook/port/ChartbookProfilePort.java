package org.zipp.ai.domain.chartbook.port;

import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfile;
import org.zipp.ai.domain.chartbook.model.valobj.ChartbookProfilePatch;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;

import java.time.Instant;

/** Persistence boundary for the independent, versioned Chartbook Profile. */
public interface ChartbookProfilePort {
    ChartbookProfile find(CatalogOwner owner, String chartbookId);

    ChartbookProfile update(CatalogOwner owner, String chartbookId, ChartbookProfilePatch patch,
                            long expectedVersion, String idempotencyKey, Instant now);
}
