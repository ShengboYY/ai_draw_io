package org.zipp.ai.domain.ingestion.service;

import org.zipp.ai.domain.ingestion.model.valobj.NativeTextQuality;

import java.util.Objects;

public final class OcrSelectionPolicy {

    private final int minimumCharacters;
    private final double maximumUnicodeAnomalyRatio;
    private final double maximumDuplicateGlyphRatio;
    private final double minimumCoverageRatio;
    private final double minimumRasterRegionArea;

    public OcrSelectionPolicy(int minimumCharacters, double maximumUnicodeAnomalyRatio,
                              double maximumDuplicateGlyphRatio, double minimumCoverageRatio) {
        this(minimumCharacters, maximumUnicodeAnomalyRatio, maximumDuplicateGlyphRatio,
                minimumCoverageRatio, 0.03);
    }

    public OcrSelectionPolicy(int minimumCharacters, double maximumUnicodeAnomalyRatio,
                              double maximumDuplicateGlyphRatio, double minimumCoverageRatio,
                              double minimumRasterRegionArea) {
        if (minimumCharacters < 1 || !ratio(maximumUnicodeAnomalyRatio)
                || !ratio(maximumDuplicateGlyphRatio) || !ratio(minimumCoverageRatio)
                || !ratio(minimumRasterRegionArea)) {
            throw new IllegalArgumentException("OCR selection thresholds are invalid");
        }
        this.minimumCharacters = minimumCharacters;
        this.maximumUnicodeAnomalyRatio = maximumUnicodeAnomalyRatio;
        this.maximumDuplicateGlyphRatio = maximumDuplicateGlyphRatio;
        this.minimumCoverageRatio = minimumCoverageRatio;
        this.minimumRasterRegionArea = minimumRasterRegionArea;
    }

    public boolean requiresOcr(String mediaType, NativeTextQuality quality) {
        return requiresOcr(mediaType, quality, java.util.List.of());
    }

    public boolean requiresOcr(String mediaType, NativeTextQuality quality,
                               java.util.List<org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox>
                                       rasterRegions) {
        String type = mediaType == null ? "" : mediaType.trim().toLowerCase(java.util.Locale.ROOT);
        NativeTextQuality facts = Objects.requireNonNull(quality, "quality");
        if (type.startsWith("image/")) {
            return true;
        }
        boolean containsReadableRasterRegion = Objects.requireNonNull(rasterRegions, "rasterRegions").stream()
                .anyMatch(region -> (region.x2() - region.x1()) * (region.y2() - region.y1())
                        >= minimumRasterRegionArea);
        return facts.effectiveCharacters() < minimumCharacters
                || facts.unicodeAnomalyRatio() > maximumUnicodeAnomalyRatio
                || facts.duplicateGlyphRatio() > maximumDuplicateGlyphRatio
                || facts.textCoverageRatio() < minimumCoverageRatio
                || containsReadableRasterRegion;
    }

    public String fingerprint() {
        return "ocr-select-v2:" + minimumCharacters + ":" + maximumUnicodeAnomalyRatio + ":"
                + maximumDuplicateGlyphRatio + ":" + minimumCoverageRatio + ":" + minimumRasterRegionArea;
    }

    private static boolean ratio(double value) {
        return value >= 0 && value <= 1 && !Double.isNaN(value);
    }
}
