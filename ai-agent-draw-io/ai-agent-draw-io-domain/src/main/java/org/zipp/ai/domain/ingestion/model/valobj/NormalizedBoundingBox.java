package org.zipp.ai.domain.ingestion.model.valobj;

public record NormalizedBoundingBox(double x1, double y1, double x2, double y2) {
    public NormalizedBoundingBox {
        if (!finite(x1) || !finite(y1) || !finite(x2) || !finite(y2)
                || x1 < 0 || y1 < 0 || x2 > 1 || y2 > 1 || x1 >= x2 || y1 >= y2) {
            throw new IllegalArgumentException("bounding box must be a non-empty normalized rectangle");
        }
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
