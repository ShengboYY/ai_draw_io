package org.zipp.ai.domain.operations;

/** Content-free no-answer calibration evidence for one reported evaluation slice. */
public record RagCalibrationSlice(String sliceKey, RagCalibrationFallback fallback,
                                  int sampleCount, double falseSupportedRate,
                                  double falseSupportedConfidenceLow,
                                  double falseSupportedConfidenceHigh,
                                  double falseAbstentionRate,
                                  double falseAbstentionConfidenceLow,
                                  double falseAbstentionConfidenceHigh) {
    public RagCalibrationSlice {
        if (sliceKey == null || sliceKey.isBlank()) throw new IllegalArgumentException("sliceKey is required");
        sliceKey = sliceKey.trim();
        if (fallback == null || sampleCount < 0) throw new IllegalArgumentException("calibration fallback is invalid");
        validateInterval(falseSupportedRate, falseSupportedConfidenceLow, falseSupportedConfidenceHigh);
        validateInterval(falseAbstentionRate, falseAbstentionConfidenceLow, falseAbstentionConfidenceHigh);
    }

    public boolean hasEnoughSamples() {
        return sampleCount >= fallback.minimumSamples();
    }

    private static void validateInterval(double rate, double low, double high) {
        if (!Double.isFinite(rate) || !Double.isFinite(low) || !Double.isFinite(high)
                || low < 0D || rate < low || high < rate || high > 1D) {
            throw new IllegalArgumentException("calibration rate and confidence interval are invalid");
        }
    }
}
