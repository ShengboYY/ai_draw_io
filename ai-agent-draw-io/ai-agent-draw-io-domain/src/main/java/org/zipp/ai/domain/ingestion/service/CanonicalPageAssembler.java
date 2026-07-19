package org.zipp.ai.domain.ingestion.service;

import org.zipp.ai.domain.ingestion.model.valobj.CanonicalBlock;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPage;
import org.zipp.ai.domain.ingestion.model.valobj.BoilerplatePosition;
import org.zipp.ai.domain.ingestion.model.valobj.ExtractedTextBlock;
import org.zipp.ai.domain.ingestion.model.valobj.OcrWord;
import org.zipp.ai.domain.ingestion.model.valobj.PageExtraction;
import org.zipp.ai.domain.ingestion.model.valobj.SourceMapSpan;
import org.zipp.ai.domain.ingestion.model.valobj.TextBlockKind;
import org.zipp.ai.domain.ingestion.model.valobj.TextSource;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CanonicalPageAssembler {

    private final double lowConfidenceThreshold;
    private final TextSourceQualityCalibration qualityCalibration;

    public CanonicalPageAssembler(double lowConfidenceThreshold) {
        this(lowConfidenceThreshold, TextSourceQualityCalibration.goldenV1());
    }

    public CanonicalPageAssembler(double lowConfidenceThreshold,
                                  TextSourceQualityCalibration qualityCalibration) {
        if (lowConfidenceThreshold < 0 || lowConfidenceThreshold > 1) {
            throw new IllegalArgumentException("lowConfidenceThreshold must be a ratio");
        }
        this.lowConfidenceThreshold = lowConfidenceThreshold;
        this.qualityCalibration = java.util.Objects.requireNonNull(qualityCalibration, "qualityCalibration");
    }

    public double lowConfidenceThreshold() {
        return lowConfidenceThreshold;
    }

    public String fingerprint() {
        return "canonical-v3:source-map-v2:block-region-merge:reading-flow-v1:boilerplate-candidate-v1"
                + ":calibration=" + qualityCalibration.version() + ":low-confidence=" + lowConfidenceThreshold;
    }

    public CanonicalPage assemble(PageExtraction page) {
        List<CanonicalBlock> nativeCandidates = collapseDuplicateNativeBlocks(nativeBlocks(page.nativeBlocks()));
        List<CanonicalBlock> blocks = mergeByRegion(nativeCandidates,
                page.ocrResult() == null ? List.of() : ocrBlocks(page));
        Double ocrConfidence = page.ocrResult() == null ? null : page.ocrResult().confidence();
        return new CanonicalPage(page.pageNo(), page.width(), page.height(), blocks,
                page.nativeTextQuality(), ocrConfidence,
                ocrConfidence != null && ocrConfidence < lowConfidenceThreshold);
    }

    private List<CanonicalBlock> mergeByRegion(List<CanonicalBlock> nativeBlocks,
                                                List<CanonicalBlock> ocrBlocks) {
        List<CanonicalBlock> selected = new ArrayList<>(nativeBlocks);
        for (CanonicalBlock ocr : ocrBlocks) {
            List<CanonicalBlock> overlapping = selected.stream()
                    .filter(block -> block.textSource() == TextSource.NATIVE && overlaps(block, ocr)).toList();
            if (overlapping.isEmpty()) {
                boolean duplicate = selected.stream().anyMatch(block -> equivalentText(block.displayText(),
                        ocr.displayText()));
                if (!duplicate) {
                    selected.add(ocr);
                }
                continue;
            }
            CanonicalBlock strongestNative = overlapping.stream()
                    .max(java.util.Comparator.comparingDouble(this::calibratedQuality))
                    .orElseThrow();
            if (calibratedQuality(ocr) > calibratedQuality(strongestNative)) {
                selected.removeAll(overlapping);
                selected.add(ocr);
            }
        }
        selected = orderByReadingFlow(selected);
        List<CanonicalBlock> ordered = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            CanonicalBlock block = selected.get(index);
            ordered.add(new CanonicalBlock(block.blockId(), block.kind(), index + 1, block.regions(),
                    block.textSource(), block.extractedText(), block.displayText(), block.sourceMap(),
                    block.confidence(), boilerplatePosition(block)));
        }
        return List.copyOf(ordered);
    }

    private static boolean overlaps(CanonicalBlock first, CanonicalBlock second) {
        return first.regions().stream().anyMatch(a -> second.regions().stream()
                .anyMatch(b -> overlapRatio(a, b) >= 0.50));
    }

    private static List<CanonicalBlock> collapseDuplicateNativeBlocks(List<CanonicalBlock> blocks) {
        List<CanonicalBlock> collapsed = new ArrayList<>();
        for (CanonicalBlock candidate : blocks) {
            int duplicateIndex = -1;
            for (int index = 0; index < collapsed.size(); index++) {
                CanonicalBlock existing = collapsed.get(index);
                if (equivalentText(existing.displayText(), candidate.displayText())
                        && overlaps(existing, candidate)) {
                    duplicateIndex = index;
                    break;
                }
            }
            if (duplicateIndex < 0) {
                collapsed.add(candidate);
                continue;
            }
            CanonicalBlock representative = collapsed.get(duplicateIndex);
            // Duplicate PDF text runs can be split across callbacks; penalize the retained occurrence so OCR
            // selected by page-level duplicate detection can still replace the damaged native region.
            collapsed.set(duplicateIndex, new CanonicalBlock(representative.blockId(), representative.kind(),
                    representative.readingOrder(), representative.regions(), representative.textSource(),
                    representative.extractedText(), representative.displayText(), representative.sourceMap(),
                    Math.min(representative.confidence(), candidate.confidence()) * 0.75,
                    representative.boilerplatePosition()));
        }
        return List.copyOf(collapsed);
    }

    private static double overlapRatio(
            org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox first,
            org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox second) {
        double width = Math.max(0, Math.min(first.x2(), second.x2()) - Math.max(first.x1(), second.x1()));
        double height = Math.max(0, Math.min(first.y2(), second.y2()) - Math.max(first.y1(), second.y1()));
        double firstArea = (first.x2() - first.x1()) * (first.y2() - first.y1());
        double secondArea = (second.x2() - second.x1()) * (second.y2() - second.y1());
        return width * height / Math.min(firstArea, secondArea);
    }

    private double calibratedQuality(CanonicalBlock block) {
        long effective = block.displayText().codePoints().filter(codePoint -> !Character.isWhitespace(codePoint))
                .count();
        long anomalies = block.displayText().codePoints()
                .filter(codePoint -> codePoint == 0xfffd || Character.isISOControl(codePoint)).count();
        double legibility = effective == 0 ? 0 : 1.0 - (double) anomalies / effective;
        return qualityCalibration.calibrate(block.textSource(), block.confidence(), legibility);
    }

    private static List<CanonicalBlock> orderByReadingFlow(List<CanonicalBlock> blocks) {
        List<CanonicalBlock> remaining = new ArrayList<>(blocks);
        remaining.sort(java.util.Comparator.comparingDouble(CanonicalPageAssembler::top)
                .thenComparingDouble(CanonicalPageAssembler::left));
        List<CanonicalBlock> separators = remaining.stream().filter(CanonicalPageAssembler::isFullWidth).toList();
        List<CanonicalBlock> bandCandidates = new ArrayList<>(remaining.stream()
                .filter(block -> !isFullWidth(block)).toList());
        List<CanonicalBlock> ordered = new ArrayList<>();
        for (CanonicalBlock separator : separators) {
            double separatorTop = top(separator);
            List<CanonicalBlock> band = bandCandidates.stream()
                    .filter(block -> top(block) < separatorTop).toList();
            ordered.addAll(orderBand(band));
            bandCandidates.removeAll(band);
            ordered.add(separator);
        }
        ordered.addAll(orderBand(bandCandidates));
        return List.copyOf(ordered);
    }

    private static List<CanonicalBlock> orderBand(List<CanonicalBlock> blocks) {
        if (blocks.size() < 2) {
            return blocks;
        }
        List<CanonicalBlock> leftColumn = blocks.stream().filter(block -> horizontalCenter(block) < 0.50)
                .sorted(java.util.Comparator.comparingDouble(CanonicalPageAssembler::top)).toList();
        List<CanonicalBlock> rightColumn = blocks.stream().filter(block -> horizontalCenter(block) >= 0.50)
                .sorted(java.util.Comparator.comparingDouble(CanonicalPageAssembler::top)).toList();
        boolean separatedColumns = !leftColumn.isEmpty() && !rightColumn.isEmpty()
                && leftColumn.stream().mapToDouble(CanonicalPageAssembler::right).max().orElse(1)
                <= rightColumn.stream().mapToDouble(CanonicalPageAssembler::left).min().orElse(0) + 0.03
                && verticalRangesOverlap(leftColumn, rightColumn);
        if (!separatedColumns) {
            return blocks.stream().sorted(java.util.Comparator.comparingDouble(CanonicalPageAssembler::top)
                    .thenComparingDouble(CanonicalPageAssembler::left)).toList();
        }
        List<CanonicalBlock> ordered = new ArrayList<>(leftColumn);
        ordered.addAll(rightColumn);
        return List.copyOf(ordered);
    }

    private static boolean verticalRangesOverlap(List<CanonicalBlock> leftColumn,
                                                  List<CanonicalBlock> rightColumn) {
        double leftTop = leftColumn.stream().mapToDouble(CanonicalPageAssembler::top).min().orElse(1);
        double leftBottom = leftColumn.stream().mapToDouble(CanonicalPageAssembler::bottom).max().orElse(0);
        double rightTop = rightColumn.stream().mapToDouble(CanonicalPageAssembler::top).min().orElse(1);
        double rightBottom = rightColumn.stream().mapToDouble(CanonicalPageAssembler::bottom).max().orElse(0);
        return Math.min(leftBottom, rightBottom) > Math.max(leftTop, rightTop);
    }

    private static boolean isFullWidth(CanonicalBlock block) {
        return right(block) - left(block) >= 0.65 || left(block) <= 0.25 && right(block) >= 0.75;
    }

    private static BoilerplatePosition boilerplatePosition(CanonicalBlock block) {
        if (top(block) <= 0.07 && bottom(block) <= 0.12) {
            return BoilerplatePosition.HEADER_CANDIDATE;
        }
        if (top(block) >= 0.90) {
            return BoilerplatePosition.FOOTER_CANDIDATE;
        }
        return BoilerplatePosition.NONE;
    }

    private static boolean equivalentText(String first, String second) {
        return first.replaceAll("\\s+", "").equalsIgnoreCase(second.replaceAll("\\s+", ""));
    }

    private static double top(CanonicalBlock block) {
        return block.regions().stream().mapToDouble(
                org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox::y1).min().orElse(1);
    }

    private static double left(CanonicalBlock block) {
        return block.regions().stream().mapToDouble(
                org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox::x1).min().orElse(1);
    }

    private static double right(CanonicalBlock block) {
        return block.regions().stream().mapToDouble(
                org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox::x2).max().orElse(0);
    }

    private static double bottom(CanonicalBlock block) {
        return block.regions().stream().mapToDouble(
                org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox::y2).max().orElse(0);
    }

    private static double horizontalCenter(CanonicalBlock block) {
        return (left(block) + right(block)) / 2;
    }

    private static List<CanonicalBlock> nativeBlocks(List<ExtractedTextBlock> nativeBlocks) {
        return nativeBlocks.stream().map(block -> canonical(block.blockId(), block.kind(), block.readingOrder(),
                block.regions(), block.textSource(), block.text(), block.sourceMap(), block.confidence())).toList();
    }

    private static List<CanonicalBlock> ocrBlocks(PageExtraction page) {
        if (page.ocrResult() == null || page.ocrResult().words().isEmpty()) {
            return List.of();
        }
        Map<String, List<OcrWord>> lines = new LinkedHashMap<>();
        page.ocrResult().words().forEach(word -> lines.computeIfAbsent(word.lineId(), ignored -> new ArrayList<>())
                .add(word));
        List<CanonicalBlock> blocks = new ArrayList<>();
        int order = 1;
        for (List<OcrWord> words : lines.values()) {
            String text = words.stream().map(OcrWord::text).collect(java.util.stream.Collectors.joining(" "));
            double confidence = words.stream().mapToDouble(OcrWord::confidence).average().orElse(0);
            List<SourceMapSpan> sourceMap = new ArrayList<>();
            int offset = 0;
            for (int index = 0; index < words.size(); index++) {
                OcrWord word = words.get(index);
                int start = offset;
                int end = start + word.text().length();
                sourceMap.add(new SourceMapSpan(start, end, start, end, List.of(word.region())));
                offset = end;
                if (index < words.size() - 1) {
                    sourceMap.add(new SourceMapSpan(offset, offset + 1, offset, offset + 1,
                            List.of(word.region(), words.get(index + 1).region())));
                    offset++;
                }
            }
            blocks.add(new CanonicalBlock("ocr_p" + page.pageNo() + "_" + order,
                    TextBlockKind.PARAGRAPH, order, words.stream().map(OcrWord::region).toList(),
                    TextSource.OCR, text, text, sourceMap, confidence, BoilerplatePosition.NONE));
            order++;
        }
        return List.copyOf(blocks);
    }

    private static CanonicalBlock canonical(String id, TextBlockKind kind, int order,
                                             List<org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox> regions,
                                             TextSource source, String extracted,
                                             List<SourceMapSpan> extractedSourceMap, double confidence) {
        NormalizedText normalized = normalizeWithSourceMap(extracted, extractedSourceMap, regions);
        return new CanonicalBlock(id, kind, order, regions, source, normalized.extracted(),
                normalized.display(), normalized.sourceMap(), confidence, BoilerplatePosition.NONE);
    }

    private static NormalizedText normalizeWithSourceMap(
            String extracted, List<SourceMapSpan> extractedSourceMap,
            List<org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox> fallbackRegions) {
        StringBuilder display = new StringBuilder();
        List<SourceMapSpan> sourceMap = new ArrayList<>();
        java.text.BreakIterator iterator = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        iterator.setText(extracted);
        int whitespaceStart = -1;
        for (int start = iterator.first(), end = iterator.next(); end != java.text.BreakIterator.DONE;
             start = end, end = iterator.next()) {
            String unit = extracted.substring(start, end);
            if (unit.codePoints().allMatch(Character::isWhitespace)) {
                whitespaceStart = whitespaceStart < 0 ? start : whitespaceStart;
                continue;
            }
            String normalized = Normalizer.normalize(unit, Normalizer.Form.NFC)
                    .codePoints().filter(codePoint -> !Character.isISOControl(codePoint))
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();
            if (normalized.isEmpty()) {
                continue;
            }
            if (whitespaceStart >= 0 && !display.isEmpty()) {
                int displayStart = display.length();
                display.append(' ');
                sourceMap.add(new SourceMapSpan(displayStart, display.length(), whitespaceStart, start,
                        sourceRegions(extractedSourceMap, whitespaceStart, start, fallbackRegions)));
            }
            whitespaceStart = -1;
            int displayStart = display.length();
            display.append(normalized);
            sourceMap.add(new SourceMapSpan(displayStart, display.length(), start, end,
                    sourceRegions(extractedSourceMap, start, end, fallbackRegions)));
        }
        return new NormalizedText(extracted, display.toString(), List.copyOf(sourceMap));
    }

    private static List<org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox> sourceRegions(
            List<SourceMapSpan> sourceMap, int start, int end,
            List<org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox> fallback) {
        List<org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox> regions = sourceMap.stream()
                .filter(span -> span.extractedEnd() > start && span.extractedStart() < end)
                .flatMap(span -> span.regions().stream()).distinct().toList();
        return regions.isEmpty() ? fallback : regions;
    }

    private record NormalizedText(String extracted, String display, List<SourceMapSpan> sourceMap) { }
}
