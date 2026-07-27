package org.zipp.ai.domain.ingestion.model.valobj;

public record VisualAnalysisResult(int pageNo, String regionRef, String kind, String summary, double quality) {
    public VisualAnalysisResult {
        if (pageNo < 1 || quality < 0 || quality > 1) {
            throw new IllegalArgumentException("invalid visual analysis result");
        }
        regionRef = requireText(regionRef, "regionRef");
        kind = requireText(kind, "kind");
        summary = summary == null ? "" : summary;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
