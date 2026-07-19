package org.zipp.ai.domain.ingestion.model.valobj;

import java.util.List;
import java.util.Objects;

public record PageExtraction(int pageNo, double width, double height,
                             List<ExtractedTextBlock> nativeBlocks,
                             NativeTextQuality nativeTextQuality,
                             List<NormalizedBoundingBox> rasterRegions, OcrResult ocrResult) {
    public PageExtraction {
        if (pageNo < 1 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("page identity and dimensions are invalid");
        }
        nativeBlocks = List.copyOf(Objects.requireNonNull(nativeBlocks, "nativeBlocks"));
        nativeTextQuality = Objects.requireNonNull(nativeTextQuality, "nativeTextQuality");
        rasterRegions = List.copyOf(Objects.requireNonNull(rasterRegions, "rasterRegions"));
        if (ocrResult != null && ocrResult.pageNo() != pageNo) {
            throw new IllegalArgumentException("OCR result belongs to another page");
        }
    }

    public PageExtraction(int pageNo, double width, double height, List<ExtractedTextBlock> nativeBlocks,
                          NativeTextQuality nativeTextQuality, OcrResult ocrResult) {
        this(pageNo, width, height, nativeBlocks, nativeTextQuality, List.of(), ocrResult);
    }

    public PageExtraction withOcr(OcrResult ocr) {
        return new PageExtraction(pageNo, width, height, nativeBlocks, nativeTextQuality, rasterRegions,
                Objects.requireNonNull(ocr, "ocr"));
    }
}
