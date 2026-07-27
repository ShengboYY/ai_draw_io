package org.zipp.ai.domain.retrieval.port;

import org.zipp.ai.domain.retrieval.model.valobj.GenerationBackfillStatus;
import org.zipp.ai.domain.retrieval.model.valobj.GenerationShadowReport;
import org.zipp.ai.domain.retrieval.projection.VectorGenerationProfile;

import java.time.Instant;
import java.util.Optional;

/** Durable boundary for compatibility backfill, shadow evidence, and atomic generation switching. */
public interface IndexGenerationCompatibilityPort {
    GenerationBackfillStatus synchronize(VectorGenerationProfile profile, int batchSize, Instant now);
    boolean beginShadow(GenerationBackfillStatus expected, Instant now);
    boolean recordShadowReport(GenerationShadowReport report);
    Optional<GenerationBackfillStatus> findStatus(String generationId);
    Optional<GenerationShadowReport> findShadowReport(String generationId, String reportId);
    boolean activate(GenerationBackfillStatus expected, GenerationShadowReport report,
                     Instant activatedAt, Instant rollbackUntil);
    boolean rollback(String activeGenerationId, Instant rolledBackAt);
}
