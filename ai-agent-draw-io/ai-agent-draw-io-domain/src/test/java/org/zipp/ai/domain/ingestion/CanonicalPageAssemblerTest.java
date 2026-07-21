package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPage;
import org.zipp.ai.domain.ingestion.model.valobj.BoilerplatePosition;
import org.zipp.ai.domain.ingestion.model.valobj.ExtractedTextBlock;
import org.zipp.ai.domain.ingestion.model.valobj.NativeTextQuality;
import org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox;
import org.zipp.ai.domain.ingestion.model.valobj.OcrResult;
import org.zipp.ai.domain.ingestion.model.valobj.OcrWord;
import org.zipp.ai.domain.ingestion.model.valobj.PageExtraction;
import org.zipp.ai.domain.ingestion.model.valobj.TextBlockKind;
import org.zipp.ai.domain.ingestion.model.valobj.TextSource;
import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.TextSourceQualityCalibration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalPageAssemblerTest {

    private static final NormalizedBoundingBox BOX = new NormalizedBoundingBox(0.1, 0.2, 0.8, 0.3);

    @Test
    void healthyNativeTextWinsWithoutConcatenatingDuplicateOcrText() {
        ExtractedTextBlock nativeBlock = new ExtractedTextBlock(
                "b_1", TextBlockKind.PARAGRAPH, 1, List.of(BOX), TextSource.NATIVE,
                "Agile delivery", 0.98);
        OcrResult ocr = new OcrResult(1, "Agile delivery duplicate", 0.96,
                List.of(new OcrWord("Agile", BOX, 0.96, "line:1")));
        PageExtraction page = new PageExtraction(1, 595, 842, List.of(nativeBlock),
                new NativeTextQuality(200, 0, 0, 0.20), ocr);

        CanonicalPage canonical = new CanonicalPageAssembler(0.70).assemble(page);

        assertEquals(1, canonical.blocks().size());
        assertEquals(TextSource.NATIVE, canonical.blocks().get(0).textSource());
        assertEquals("Agile delivery", canonical.blocks().get(0).displayText());
        assertFalse(canonical.ocrLowConfidence());
    }

    @Test
    void scannedPageUsesOcrWordsAndRetainsAQueryableSourceMap() {
        OcrResult ocr = new OcrResult(1, "Sprint Review", 0.64, List.of(
                new OcrWord("Sprint", new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.3), 0.65, "line:1"),
                new OcrWord("Review", new NormalizedBoundingBox(0.32, 0.2, 0.55, 0.3), 0.63, "line:1")));
        PageExtraction page = new PageExtraction(1, 1000, 1400, List.of(),
                NativeTextQuality.empty(), ocr);

        CanonicalPage canonical = new CanonicalPageAssembler(0.70).assemble(page);

        assertEquals("Sprint Review", canonical.blocks().get(0).displayText());
        assertEquals(TextSource.OCR, canonical.blocks().get(0).textSource());
        assertEquals(2, canonical.blocks().get(0).regions().size());
        assertEquals(3, canonical.blocks().get(0).sourceMap().size());
        assertEquals(0, canonical.blocks().get(0).sourceMap().get(0).displayStart());
        assertEquals(6, canonical.blocks().get(0).sourceMap().get(0).displayEnd());
        assertEquals(List.of(new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.3)),
                canonical.blocks().get(0).sourceMap().get(0).regions());
        assertTrue(canonical.ocrLowConfidence());
    }

    @Test
    void classifiesOcrListAndCaptionLinesUsingTheSharedDeterministicPolicy() {
        OcrResult ocr = new OcrResult(1, "- Plan Figure 1. Sprint loop", 0.90, List.of(
                new OcrWord("-", new NormalizedBoundingBox(0.1, 0.2, 0.12, 0.23), 0.90, "line:1"),
                new OcrWord("Plan", new NormalizedBoundingBox(0.13, 0.2, 0.3, 0.23), 0.90, "line:1"),
                new OcrWord("Figure", new NormalizedBoundingBox(0.1, 0.5, 0.2, 0.53), 0.90, "line:2"),
                new OcrWord("1.", new NormalizedBoundingBox(0.21, 0.5, 0.24, 0.53), 0.90, "line:2"),
                new OcrWord("Sprint", new NormalizedBoundingBox(0.25, 0.5, 0.35, 0.53), 0.90, "line:2"),
                new OcrWord("loop", new NormalizedBoundingBox(0.36, 0.5, 0.44, 0.53), 0.90, "line:2")));

        CanonicalPage canonical = new CanonicalPageAssembler(0.70).assemble(new PageExtraction(
                1, 1000, 1400, List.of(), NativeTextQuality.empty(), ocr));

        assertEquals(List.of(TextBlockKind.LIST_ITEM, TextBlockKind.CAPTION), canonical.blocks().stream()
                .map(block -> block.kind()).toList());
    }

    @Test
    void mergesAlignedOcrRowsWithTheSameDelimitedShapeIntoATable() {
        OcrResult ocr = new OcrResult(1, "Name Value A 1", 0.90, List.of(
                word("Name", 0.10, 0.20, 0.25, 0.22, "line:1"),
                word("|Value", 0.26, 0.20, 0.42, 0.22, "line:1"),
                word("A", 0.10, 0.23, 0.25, 0.25, "line:2"),
                word("|1", 0.26, 0.23, 0.42, 0.25, "line:2")));

        CanonicalPage canonical = new CanonicalPageAssembler(0.70).assemble(new PageExtraction(
                1, 1000, 1400, List.of(), NativeTextQuality.empty(), ocr));

        assertEquals(1, canonical.blocks().size());
        assertEquals(TextBlockKind.TABLE, canonical.blocks().get(0).kind());
        assertEquals("Name |Value\nA |1", canonical.blocks().get(0).displayText());
        assertEquals(0, canonical.blocks().get(0).tableHeaderRowCount());
    }

    @Test
    void nativeWhitespaceNormalizationKeepsExactCharacterOffsets() {
        ExtractedTextBlock nativeBlock = new ExtractedTextBlock(
                "b_1", TextBlockKind.PARAGRAPH, 1, List.of(BOX), TextSource.NATIVE,
                "  Agile\t\tflow  ", 0.98);
        PageExtraction page = new PageExtraction(1, 595, 842, List.of(nativeBlock),
                new NativeTextQuality(200, 0, 0, 0.20), null);

        var block = new CanonicalPageAssembler(0.70).assemble(page).blocks().get(0);

        assertEquals("Agile flow", block.displayText());
        assertEquals(2, block.sourceMap().get(0).extractedStart());
        assertEquals(7, block.sourceMap().get(5).extractedStart());
        assertEquals(9, block.sourceMap().get(5).extractedEnd());
    }

    @Test
    void mixedPdfKeepsGoodNativeBlockAndAddsNonOverlappingScannedRegion() {
        NormalizedBoundingBox nativeRegion = new NormalizedBoundingBox(0.1, 0.1, 0.8, 0.2);
        ExtractedTextBlock nativeBlock = new ExtractedTextBlock("native", TextBlockKind.PARAGRAPH, 1,
                List.of(nativeRegion), TextSource.NATIVE, "Native introduction", 0.98);
        OcrResult ocr = new OcrResult(1, "Scanned diagram", 0.91, List.of(
                new OcrWord("Scanned", new NormalizedBoundingBox(0.1, 0.6, 0.3, 0.7), 0.92, "line:2"),
                new OcrWord("diagram", new NormalizedBoundingBox(0.31, 0.6, 0.5, 0.7), 0.90, "line:2")));
        PageExtraction page = new PageExtraction(1, 595, 842, List.of(nativeBlock),
                new NativeTextQuality(20, 0, 0, 0.01), ocr);

        CanonicalPage canonical = new CanonicalPageAssembler(0.70).assemble(page);

        assertEquals(2, canonical.blocks().size());
        assertEquals(TextSource.NATIVE, canonical.blocks().get(0).textSource());
        assertEquals(TextSource.OCR, canonical.blocks().get(1).textSource());
    }

    @Test
    void twoColumnPageReadsTheLeftColumnBeforeTheRightColumn() {
        List<ExtractedTextBlock> blocks = List.of(
                block("left-1", "Left one", new NormalizedBoundingBox(0.05, 0.10, 0.45, 0.20)),
                block("right-1", "Right one", new NormalizedBoundingBox(0.55, 0.12, 0.95, 0.22)),
                block("left-2", "Left two", new NormalizedBoundingBox(0.05, 0.30, 0.45, 0.40)),
                block("right-2", "Right two", new NormalizedBoundingBox(0.55, 0.32, 0.95, 0.42)));

        CanonicalPage canonical = new CanonicalPageAssembler(0.70).assemble(new PageExtraction(
                1, 1000, 1400, blocks, new NativeTextQuality(100, 0, 0, 0.25), null));

        assertEquals(List.of("Left one", "Left two", "Right one", "Right two"), canonical.blocks().stream()
                .map(block -> block.displayText()).toList());
    }

    @Test
    void assemblesContiguousNativePdfLinesIntoOneCitableParagraph() {
        List<ExtractedTextBlock> blocks = List.of(
                block("nist-1", "The AI RMF is intended", new NormalizedBoundingBox(
                        0.12, 0.20, 0.56, 0.22)),
                block("nist-1b", "to help", new NormalizedBoundingBox(
                        0.57, 0.20, 0.68, 0.22)),
                block("nist-2", "organizations manage risks associated", new NormalizedBoundingBox(
                        0.12, 0.224, 0.86, 0.244)),
                block("nist-3", "with AI systems.", new NormalizedBoundingBox(
                        0.12, 0.248, 0.46, 0.268)),
                block("next-paragraph", "A separate paragraph starts here.", new NormalizedBoundingBox(
                        0.12, 0.31, 0.72, 0.33)));

        CanonicalPage canonical = new CanonicalPageAssembler(0.70).assemble(new PageExtraction(
                1, 1000, 1400, blocks, new NativeTextQuality(100, 0, 0, 0.25), null));

        // NIST-native PDF lines must remain one complete Evidence candidate before chunk projection.
        assertEquals(List.of("The AI RMF is intended to help organizations manage risks associated with AI systems.",
                        "A separate paragraph starts here."), canonical.blocks().stream()
                .map(block -> block.displayText()).toList());
        assertEquals(4, canonical.blocks().get(0).regions().size());
        assertTrue(canonical.blocks().get(0).sourceMap().stream().anyMatch(span ->
                span.displayStart() == "The AI RMF is intended to help".length()
                        && span.displayEnd() == "The AI RMF is intended to help ".length()));
    }

    @Test
    void edgeBlocksAreMarkedAsBoilerplateCandidatesForDocumentLevelConfirmation() {
        List<ExtractedTextBlock> blocks = List.of(
                block("header", "Agile Practice Guide", new NormalizedBoundingBox(0.1, 0.01, 0.9, 0.05)),
                block("body", "Iteration content", new NormalizedBoundingBox(0.1, 0.30, 0.9, 0.40)),
                block("footer", "Page 1", new NormalizedBoundingBox(0.4, 0.94, 0.6, 0.98)));

        CanonicalPage canonical = new CanonicalPageAssembler(0.70).assemble(new PageExtraction(
                1, 1000, 1400, blocks, new NativeTextQuality(100, 0, 0, 0.25), null));

        assertEquals(BoilerplatePosition.HEADER_CANDIDATE, canonical.blocks().get(0).boilerplatePosition());
        assertEquals(BoilerplatePosition.NONE, canonical.blocks().get(1).boilerplatePosition());
        assertEquals(BoilerplatePosition.FOOTER_CANDIDATE, canonical.blocks().get(2).boilerplatePosition());
    }

    @Test
    void versionedCalibrationUsesGoldenAnchorsAndKeepsHealthyNativeTextAheadOfEqualRawOcr() {
        TextSourceQualityCalibration calibration = TextSourceQualityCalibration.goldenV1();

        assertEquals("tesseract-lstm-eng-chi_sim-golden-2026-07-v1", calibration.version());
        assertTrue(calibration.calibrate(TextSource.OCR, 0.90, 1.0)
                < calibration.calibrate(TextSource.NATIVE, 0.90, 1.0));
        assertTrue(calibration.calibrate(TextSource.OCR, 0.90, 1.0)
                > calibration.calibrate(TextSource.OCR, 0.60, 1.0));
    }

    @Test
    void duplicateNativeBlocksAcrossPdfTextRunsDoNotMaskAHealthierOcrBlock() {
        ExtractedTextBlock first = block("native-1", "Agile flow", BOX);
        ExtractedTextBlock duplicate = block("native-2", "Agile flow", BOX);
        OcrResult ocr = new OcrResult(1, "Agile flow", 0.90, List.of(
                new OcrWord("Agile", new NormalizedBoundingBox(0.1, 0.2, 0.3, 0.3), 0.90, "line:1"),
                new OcrWord("flow", new NormalizedBoundingBox(0.31, 0.2, 0.5, 0.3), 0.90, "line:1")));

        CanonicalPage canonical = new CanonicalPageAssembler(0.70).assemble(new PageExtraction(
                1, 1000, 1400, List.of(first, duplicate),
                new NativeTextQuality(20, 0, 0.5, 0.1), ocr));

        assertEquals(1, canonical.blocks().size());
        assertEquals(TextSource.OCR, canonical.blocks().get(0).textSource());
    }

    private static ExtractedTextBlock block(String id, String text, NormalizedBoundingBox region) {
        return new ExtractedTextBlock(id, TextBlockKind.PARAGRAPH, 1, List.of(region), TextSource.NATIVE,
                text, 0.95);
    }

    private static OcrWord word(String text, double x1, double y1, double x2, double y2, String line) {
        return new OcrWord(text, new NormalizedBoundingBox(x1, y1, x2, y2), 0.90, line);
    }
}
