package org.zipp.ai.domain.ingestion.service;

/** Combines block-local extraction facts into the native confidence used during OCR merge. */
public final class NativeBlockConfidencePolicy {

    public String fingerprint() {
        return "native-block-confidence-v1:unicode=.45:unique-glyph=.45:source-coverage=.05:reading=.05";
    }

    public double confidence(int effectiveCharacters, int anomalousCharacters,
                             int glyphCount, int duplicateGlyphs,
                             double sourceCoverage, double readingOrderCertainty) {
        if (effectiveCharacters < 0 || anomalousCharacters < 0
                || anomalousCharacters > effectiveCharacters || glyphCount < 0
                || duplicateGlyphs < 0 || duplicateGlyphs > glyphCount
                || !ratio(sourceCoverage) || !ratio(readingOrderCertainty)) {
            throw new IllegalArgumentException("native block quality facts are invalid");
        }
        if (effectiveCharacters == 0) {
            return 0;
        }
        double legibility = 1.0 - (double) anomalousCharacters / effectiveCharacters;
        double uniqueGlyphRatio = glyphCount == 0 ? 0 : 1.0 - (double) duplicateGlyphs / glyphCount;
        return legibility * 0.45 + uniqueGlyphRatio * 0.45
                + sourceCoverage * 0.05 + readingOrderCertainty * 0.05;
    }

    private static boolean ratio(double value) {
        return value >= 0 && value <= 1 && !Double.isNaN(value);
    }
}
