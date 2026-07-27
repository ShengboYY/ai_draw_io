package org.zipp.ai.domain.multimodal;

/** Normalized image bounds in the inclusive 0..1 coordinate space. */
public record ObservationBounds(double x, double y, double width, double height) {
    public ObservationBounds {
        if (!finite(x) || !finite(y) || !finite(width) || !finite(height)
                || x < 0 || y < 0 || width <= 0 || height <= 0
                || x + width > 1.000001 || y + height > 1.000001) {
            throw new IllegalArgumentException("observation bounds must fit normalized image coordinates");
        }
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
