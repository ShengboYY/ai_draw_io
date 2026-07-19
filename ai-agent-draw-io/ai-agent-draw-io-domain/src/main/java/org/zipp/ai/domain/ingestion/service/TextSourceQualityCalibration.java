package org.zipp.ai.domain.ingestion.service;

import org.zipp.ai.domain.ingestion.model.valobj.TextSource;

import java.util.Objects;

/** Versioned quality curve used to compare native extraction and OCR on a common scale. */
public final class TextSourceQualityCalibration {

    private static final double[] OCR_INPUTS = {0.0, 0.50, 0.70, 0.90, 1.0};
    private static final double[] OCR_OUTPUTS = {0.0, 0.35, 0.58, 0.82, 0.94};
    private static final double[] NATIVE_INPUTS = {0.0, 0.50, 0.70, 0.90, 1.0};
    private static final double[] NATIVE_OUTPUTS = {0.0, 0.46, 0.68, 0.91, 0.98};

    private final String version;

    private TextSourceQualityCalibration(String version) {
        this.version = Objects.requireNonNull(version, "version");
    }

    /** Baseline curve whose anchor values are locked by the bilingual Tesseract golden set. */
    public static TextSourceQualityCalibration goldenV1() {
        return new TextSourceQualityCalibration("tesseract-lstm-eng-chi_sim-golden-2026-07-v1");
    }

    public String version() {
        return version;
    }

    public double calibrate(TextSource source, double rawConfidence, double legibility) {
        if (rawConfidence < 0 || rawConfidence > 1 || legibility < 0 || legibility > 1) {
            throw new IllegalArgumentException("quality inputs must be ratios");
        }
        double calibrated = source == TextSource.OCR
                ? interpolate(rawConfidence, OCR_INPUTS, OCR_OUTPUTS)
                : interpolate(rawConfidence, NATIVE_INPUTS, NATIVE_OUTPUTS);
        // Native extractor confidence does not observe replacement/control characters, so retain a small
        // legibility correction after calibration. OCR confidence already includes recognition uncertainty.
        return source == TextSource.NATIVE ? calibrated * (0.90 + 0.10 * legibility) : calibrated;
    }

    private static double interpolate(double value, double[] inputs, double[] outputs) {
        for (int index = 1; index < inputs.length; index++) {
            if (value <= inputs[index]) {
                double ratio = (value - inputs[index - 1]) / (inputs[index] - inputs[index - 1]);
                return outputs[index - 1] + ratio * (outputs[index] - outputs[index - 1]);
            }
        }
        return outputs[outputs.length - 1];
    }
}
