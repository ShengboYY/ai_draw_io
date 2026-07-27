package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.NativeTextQuality;
import org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrSelectionPolicyTest {

    private final OcrSelectionPolicy policy = new OcrSelectionPolicy(40, 0.10, 0.20, 0.01);

    @Test
    void imagesAlwaysRequireOcr() {
        assertTrue(policy.requiresOcr("image/png", NativeTextQuality.empty()));
    }

    @Test
    void sparseOrCorruptPdfTextRequiresOcrButHealthyNativeTextDoesNot() {
        assertTrue(policy.requiresOcr("application/pdf", new NativeTextQuality(12, 0, 0, 0.02)));
        assertTrue(policy.requiresOcr("application/pdf", new NativeTextQuality(200, 0.15, 0, 0.20)));
        assertFalse(policy.requiresOcr("application/pdf", new NativeTextQuality(200, 0.01, 0.02, 0.20)));
    }

    @Test
    void meaningfulRasterRegionTriggersOcrInsideAnOtherwiseHealthyPdfPage() {
        NativeTextQuality healthy = new NativeTextQuality(200, 0.01, 0.02, 0.20);

        assertTrue(policy.requiresOcr("application/pdf", healthy,
                java.util.List.of(new NormalizedBoundingBox(0.1, 0.4, 0.6, 0.7))));
        assertFalse(policy.requiresOcr("application/pdf", healthy,
                java.util.List.of(new NormalizedBoundingBox(0.01, 0.01, 0.05, 0.05))));
    }
}
