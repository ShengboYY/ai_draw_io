package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.TextSource;
import org.zipp.ai.domain.ingestion.service.NativeBlockConfidencePolicy;
import org.zipp.ai.domain.ingestion.service.TextSourceQualityCalibration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeBlockConfidencePolicyTest {

    @Test
    void duplicateGlyphDamageLowersNativeBelowAHealthyOcrCandidate() {
        NativeBlockConfidencePolicy policy = new NativeBlockConfidencePolicy();
        double nativeConfidence = policy.confidence(20, 0, 40, 20, 1.0, 1.0);
        TextSourceQualityCalibration calibration = TextSourceQualityCalibration.goldenV1();

        assertTrue(calibration.calibrate(TextSource.NATIVE, nativeConfidence, 1.0)
                < calibration.calibrate(TextSource.OCR, 0.90, 1.0));
    }

    @Test
    void healthyMappedAndOrderedNativeBlockRetainsFullConfidence() {
        double confidence = new NativeBlockConfidencePolicy().confidence(20, 0, 20, 0, 1.0, 1.0);

        assertTrue(confidence > 0.99);
    }
}
