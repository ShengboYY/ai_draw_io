package org.zipp.ai.domain.ingestion.model.valobj;

public record NativeTextQuality(int effectiveCharacters, double unicodeAnomalyRatio,
                                double duplicateGlyphRatio, double textCoverageRatio) {
    public NativeTextQuality {
        if (effectiveCharacters < 0 || !ratio(unicodeAnomalyRatio) || !ratio(duplicateGlyphRatio)
                || !ratio(textCoverageRatio)) {
            throw new IllegalArgumentException("native text quality values are invalid");
        }
    }

    public static NativeTextQuality empty() {
        return new NativeTextQuality(0, 0, 0, 0);
    }

    private static boolean ratio(double value) {
        return value >= 0 && value <= 1 && !Double.isNaN(value);
    }
}
