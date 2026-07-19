package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

public record CanonicalPage(int pageNo, double width, double height,
                            List<CanonicalBlock> blocks, NativeTextQuality nativeTextQuality,
                            Double ocrMeanConfidence, boolean ocrLowConfidence) {
    public CanonicalPage {
        if (pageNo < 1 || width <= 0 || height <= 0
                || (ocrMeanConfidence != null && (ocrMeanConfidence < 0 || ocrMeanConfidence > 1))) {
            throw new IllegalArgumentException("canonical page facts are invalid");
        }
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        nativeTextQuality = Objects.requireNonNull(nativeTextQuality, "nativeTextQuality");
    }
}
