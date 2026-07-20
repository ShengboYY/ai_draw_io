package org.zipp.ai.domain.material.model.valobj;

/** Safe page metadata projection; storage coordinates and extracted content never leave the port. */
public record MaterialPageSummary(int pageNo, double width, double height,
                                  String nativeTextStatus, String ocrStatus, Double ocrQuality,
                                  String visualStatus, String errorCode,
                                  boolean canonicalAvailable, boolean previewAvailable) {
    public MaterialPageSummary {
        if (pageNo < 1 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("material page geometry is invalid");
        }
        nativeTextStatus = required(nativeTextStatus, "nativeTextStatus");
        ocrStatus = required(ocrStatus, "ocrStatus");
        visualStatus = required(visualStatus, "visualStatus");
        if (ocrQuality != null && (ocrQuality < 0 || ocrQuality > 1)) {
            throw new IllegalArgumentException("ocrQuality must be between zero and one");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
