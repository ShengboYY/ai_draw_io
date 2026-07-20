package org.zipp.ai.domain.operations;

/** Section 19.5 no-answer calibration fallback hierarchy and minimum cohort size. */
public enum RagCalibrationFallback {
    LANGUAGE_MODALITY_QUERY_SHAPE(30),
    MODALITY_QUERY_SHAPE(40),
    QUERY_SHAPE_FAMILY(50),
    GLOBAL(100);

    private final int minimumSamples;

    RagCalibrationFallback(int minimumSamples) {
        this.minimumSamples = minimumSamples;
    }

    public int minimumSamples() {
        return minimumSamples;
    }
}
