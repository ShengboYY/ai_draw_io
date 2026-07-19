package org.zipp.ai.ingestion.worker.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.zipp.ai.domain.ingestion.model.valobj.ExtractedTextBlock;
import org.zipp.ai.domain.ingestion.model.valobj.NativeTextQuality;
import org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox;
import org.zipp.ai.domain.ingestion.model.valobj.PageExtraction;
import org.zipp.ai.domain.ingestion.model.valobj.ParsedDocument;
import org.zipp.ai.domain.ingestion.model.valobj.ParsedPage;
import org.zipp.ai.domain.ingestion.model.valobj.TextBlockKind;
import org.zipp.ai.domain.ingestion.model.valobj.TextSource;
import org.zipp.ai.domain.ingestion.service.NativeBlockConfidencePolicy;
import org.zipp.ai.domain.ingestion.model.valobj.SourceMapSpan;
import org.zipp.ai.domain.ingestion.port.DocumentParserPort;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PdfBoxDocumentParser implements DocumentParserPort {

    private final int renderDpi;

    public PdfBoxDocumentParser(int renderDpi) {
        if (renderDpi < 72 || renderDpi > 300) {
            throw new IllegalArgumentException("renderDpi must be between 72 and 300");
        }
        this.renderDpi = renderDpi;
    }

    @Override
    public ParsedDocument parse(Path original, String detectedMediaType, Path workingDirectory) {
        Path source = java.util.Objects.requireNonNull(original, "original");
        String mediaType = detectedMediaType == null ? "" : detectedMediaType.trim().toLowerCase(Locale.ROOT);
        Path output = java.util.Objects.requireNonNull(workingDirectory, "workingDirectory");
        try {
            Files.createDirectories(output);
            if ("application/pdf".equals(mediaType)) {
                return parsePdf(source, output);
            }
            if (mediaType.startsWith("image/")) {
                return parseImage(source, output);
            }
            throw new IllegalArgumentException("unsupported extraction media type");
        } catch (IOException e) {
            throw new IllegalStateException("document extraction failed", e);
        }
    }

    private ParsedDocument parsePdf(Path original, Path output) throws IOException {
        try (PDDocument document = Loader.loadPDF(original.toFile())) {
            NativeTextStripper stripper = new NativeTextStripper(document);
            stripper.setSortByPosition(true);
            stripper.getText(document);
            PDFRenderer renderer = new PDFRenderer(document);
            List<ParsedPage> pages = new ArrayList<>();
            for (int index = 0; index < document.getNumberOfPages(); index++) {
                Path rendered = output.resolve("page-" + (index + 1) + ".png");
                BufferedImage image = renderer.renderImageWithDPI(index, renderDpi, ImageType.RGB);
                if (!ImageIO.write(image, "png", rendered.toFile())) {
                    throw new IllegalStateException("PNG writer is unavailable");
                }
                PDPage page = document.getPage(index);
                double pageWidth = displayedWidth(page);
                double pageHeight = displayedHeight(page);
                PageExtraction extraction = new PageExtraction(index + 1,
                        pageWidth, pageHeight, stripper.blocks(index), stripper.quality(index),
                        PdfRasterRegionExtractor.extract(page, pageWidth, pageHeight), null);
                pages.add(new ParsedPage(extraction, rendered));
            }
            return new ParsedDocument(pages);
        }
    }

    private ParsedDocument parseImage(Path original, Path output) throws IOException {
        BufferedImage image = ImagePageNormalizer.read(original);
        Path rendered = output.resolve("page-1.png");
        if (!ImageIO.write(image, "png", rendered.toFile())) {
            throw new IllegalStateException("PNG writer is unavailable");
        }
        PageExtraction extraction = new PageExtraction(1, image.getWidth(), image.getHeight(),
                List.of(), NativeTextQuality.empty(), null);
        return new ParsedDocument(List.of(new ParsedPage(extraction, rendered)));
    }

    private static final class NativeTextStripper extends PDFTextStripper {
        private final List<PageAccumulator> pages;
        private int currentPage;

        private NativeTextStripper(PDDocument document) throws IOException {
            pages = new ArrayList<>(document.getNumberOfPages());
            for (PDPage page : document.getPages()) {
                pages.add(new PageAccumulator(displayedWidth(page), displayedHeight(page)));
            }
        }

        @Override
        protected void startPage(PDPage page) throws IOException {
            currentPage++;
            super.startPage(page);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws IOException {
            if (text != null && !text.isBlank() && positions != null && !positions.isEmpty()) {
                pages.get(currentPage - 1).add(text, positions);
            }
            super.writeString(text, positions);
        }

        private List<ExtractedTextBlock> blocks(int zeroBasedPage) {
            return List.copyOf(pages.get(zeroBasedPage).blocks);
        }

        private NativeTextQuality quality(int zeroBasedPage) {
            return pages.get(zeroBasedPage).quality();
        }
    }

    private static double displayedWidth(PDPage page) {
        return normalizedRotation(page) % 180 == 0
                ? page.getCropBox().getWidth() : page.getCropBox().getHeight();
    }

    private static double displayedHeight(PDPage page) {
        return normalizedRotation(page) % 180 == 0
                ? page.getCropBox().getHeight() : page.getCropBox().getWidth();
    }

    private static int normalizedRotation(PDPage page) {
        return Math.floorMod(page.getRotation(), 360);
    }

    private static final class PageAccumulator {
        private static final NativeBlockConfidencePolicy BLOCK_CONFIDENCE = new NativeBlockConfidencePolicy();
        private final double width;
        private final double height;
        private final List<ExtractedTextBlock> blocks = new ArrayList<>();
        private final Set<String> glyphLocations = new HashSet<>();
        private int glyphs;
        private int duplicateGlyphs;
        private int anomalousCharacters;
        private int effectiveCharacters;
        private double coveredArea;

        private PageAccumulator(double width, double height) {
            this.width = width;
            this.height = height;
        }

        private void add(String text, List<TextPosition> positions) {
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = 0;
            double maxY = 0;
            List<NormalizedBoundingBox> glyphRegions = new ArrayList<>();
            List<SourceMapSpan> sourceMap = new ArrayList<>();
            Set<String> blockGlyphLocations = new HashSet<>();
            int textCursor = 0;
            int blockGlyphs = 0;
            int blockDuplicateGlyphs = 0;
            int mappedCharacters = 0;
            for (TextPosition position : positions) {
                double x = position.getXDirAdj();
                double y = position.getYDirAdj();
                minX = Math.min(minX, x);
                minY = Math.min(minY, y - position.getHeightDir());
                maxX = Math.max(maxX, x + position.getWidthDirAdj());
                maxY = Math.max(maxY, y);
                NormalizedBoundingBox glyphRegion = box(x, y - position.getHeightDir(),
                        x + position.getWidthDirAdj(), y, width, height);
                glyphRegions.add(glyphRegion);
                String unicode = position.getUnicode();
                int sourceStart = unicode == null || unicode.isEmpty() ? -1 : text.indexOf(unicode, textCursor);
                if (sourceStart >= 0) {
                    int sourceEnd = sourceStart + unicode.length();
                    sourceMap.add(new SourceMapSpan(sourceStart, sourceEnd, sourceStart, sourceEnd,
                            List.of(glyphRegion)));
                    textCursor = sourceEnd;
                    mappedCharacters += (int) unicode.codePoints()
                            .filter(codePoint -> !Character.isWhitespace(codePoint)).count();
                }
                glyphs++;
                String key = position.getUnicode() + ":" + Math.round(x * 10) + ":" + Math.round(y * 10);
                if (!glyphLocations.add(key)) {
                    duplicateGlyphs++;
                }
                blockGlyphs++;
                if (!blockGlyphLocations.add(key)) {
                    blockDuplicateGlyphs++;
                }
            }
            NormalizedBoundingBox box = box(minX, minY, maxX, maxY, width, height);
            coveredArea += (box.x2() - box.x1()) * (box.y2() - box.y1());
            int blockEffectiveCharacters = 0;
            int blockAnomalousCharacters = 0;
            for (int codePoint : text.codePoints().toArray()) {
                if (Character.isWhitespace(codePoint)) {
                    continue;
                }
                blockEffectiveCharacters++;
                if (codePoint == 0xfffd || Character.isISOControl(codePoint)) {
                    blockAnomalousCharacters++;
                }
            }
            effectiveCharacters += blockEffectiveCharacters;
            anomalousCharacters += blockAnomalousCharacters;
            double sourceCoverage = blockEffectiveCharacters == 0 ? 0
                    : Math.min(1, (double) mappedCharacters / blockEffectiveCharacters);
            blocks.add(new ExtractedTextBlock("native_" + (blocks.size() + 1), TextBlockKind.PARAGRAPH,
                    blocks.size() + 1, glyphRegions.isEmpty() ? List.of(box) : glyphRegions, TextSource.NATIVE, text,
                    completeSourceMap(text, sourceMap, List.of(box)),
                    BLOCK_CONFIDENCE.confidence(blockEffectiveCharacters, blockAnomalousCharacters,
                            blockGlyphs, blockDuplicateGlyphs, sourceCoverage,
                            readingOrderCertainty(positions))));
        }

        private static double readingOrderCertainty(List<TextPosition> positions) {
            if (positions.size() < 2) {
                return 1;
            }
            int orderedTransitions = 0;
            for (int index = 1; index < positions.size(); index++) {
                TextPosition previous = positions.get(index - 1);
                TextPosition current = positions.get(index);
                double lineTolerance = Math.max(previous.getHeightDir(), current.getHeightDir()) * 0.75;
                boolean sameLine = Math.abs(current.getYDirAdj() - previous.getYDirAdj()) <= lineTolerance;
                if (sameLine ? current.getXDirAdj() + 0.5 >= previous.getXDirAdj()
                        : current.getYDirAdj() + 0.5 >= previous.getYDirAdj()) {
                    orderedTransitions++;
                }
            }
            return (double) orderedTransitions / (positions.size() - 1);
        }

        private static List<SourceMapSpan> completeSourceMap(String text, List<SourceMapSpan> glyphSpans,
                                                              List<NormalizedBoundingBox> fallback) {
            if (glyphSpans.isEmpty()) {
                return List.of(new SourceMapSpan(0, text.length(), 0, text.length(), fallback));
            }
            List<SourceMapSpan> complete = new ArrayList<>();
            int cursor = 0;
            for (int index = 0; index < glyphSpans.size(); index++) {
                SourceMapSpan glyph = glyphSpans.get(index);
                if (glyph.extractedStart() > cursor) {
                    List<NormalizedBoundingBox> adjacent = new ArrayList<>();
                    if (!complete.isEmpty()) {
                        adjacent.addAll(complete.get(complete.size() - 1).regions());
                    }
                    adjacent.addAll(glyph.regions());
                    complete.add(new SourceMapSpan(cursor, glyph.extractedStart(), cursor,
                            glyph.extractedStart(), adjacent.stream().distinct().toList()));
                }
                if (glyph.extractedStart() >= cursor) {
                    complete.add(glyph);
                    cursor = glyph.extractedEnd();
                }
            }
            if (cursor < text.length()) {
                complete.add(new SourceMapSpan(cursor, text.length(), cursor, text.length(),
                        complete.get(complete.size() - 1).regions()));
            }
            return List.copyOf(complete);
        }

        private NativeTextQuality quality() {
            return new NativeTextQuality(effectiveCharacters,
                    effectiveCharacters == 0 ? 0 : (double) anomalousCharacters / effectiveCharacters,
                    glyphs == 0 ? 0 : (double) duplicateGlyphs / glyphs,
                    Math.min(1, coveredArea));
        }

        private static NormalizedBoundingBox box(double minX, double minY, double maxX, double maxY,
                                                 double width, double height) {
            double x1 = clamp(minX / width);
            double y1 = clamp(minY / height);
            double x2 = clamp(maxX / width);
            double y2 = clamp(maxY / height);
            double epsilonX = Math.min(1, 1 / width);
            double epsilonY = Math.min(1, 1 / height);
            if (x2 <= x1) {
                x2 = Math.min(1, x1 + epsilonX);
                x1 = x2 == x1 ? Math.max(0, x1 - epsilonX) : x1;
            }
            if (y2 <= y1) {
                y2 = Math.min(1, y1 + epsilonY);
                y1 = y2 == y1 ? Math.max(0, y1 - epsilonY) : y1;
            }
            return new NormalizedBoundingBox(x1, y1, x2, y2);
        }

        private static double clamp(double value) {
            return Math.max(0, Math.min(1, value));
        }
    }
}
