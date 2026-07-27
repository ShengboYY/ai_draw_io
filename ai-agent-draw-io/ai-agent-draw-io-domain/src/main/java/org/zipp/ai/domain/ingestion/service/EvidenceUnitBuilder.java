package org.zipp.ai.domain.ingestion.service;

import org.zipp.ai.domain.ingestion.model.valobj.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Deterministically derives citable source boundaries without adding model-generated claims. */
public final class EvidenceUnitBuilder {

    private static final String SCHEMA_VERSION = "evidence-manifest-v1";
    private static final int MAX_PARAGRAPH_CHARACTERS = 2_000;
    private static final int TABLE_ROWS_PER_GROUP = 6;

    public String fingerprint() {
        return SCHEMA_VERSION
                + ":paragraph-max2000-sentence-space-codepoint:implicit-list-run-indent2pct-max8:"
                + "table-explicit-header-row6:"
                + "confirmed-boilerplate-excluded:section-heading-order-v1:relations-v2";
    }

    public EvidenceManifest build(String revisionId, String versionId, DocumentStructure structure,
                                  List<EvidenceSourcePage> sourcePages, VisualCropManifest visualManifest) {
        String revision = requireText(revisionId, "revisionId");
        String version = requireText(versionId, "versionId");
        DocumentStructure document = Objects.requireNonNull(structure, "structure");
        VisualCropManifest visuals = Objects.requireNonNull(visualManifest, "visualManifest");
        if (!document.structureHash().equals(visuals.structureHash())) {
            throw new IllegalArgumentException("visual manifest must belong to the document structure");
        }
        List<EvidenceSourcePage> pages = sourcePages.stream()
                .sorted(Comparator.comparingInt(source -> source.page().pageNo())).toList();
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("evidence build requires canonical pages");
        }
        Set<String> boilerplate = new HashSet<>();
        Map<Integer, EvidenceSourcePage> sourceByPage = new HashMap<>();
        pages.forEach(page -> sourceByPage.put(page.page().pageNo(), page));
        document.boilerplateBlocks().forEach(ref -> boilerplate.add(ref.pageNo() + ":" + ref.blockId()));
        List<EvidenceUnit> textUnits = new ArrayList<>();
        List<EvidenceRelation> structuralRelations = new ArrayList<>();
        Map<String, String> evidenceByBlock = new HashMap<>();
        for (EvidenceSourcePage source : pages) {
            List<CanonicalBlock> blocks = source.page().blocks().stream()
                    .sorted(Comparator.comparingInt(CanonicalBlock::readingOrder)).toList();
            for (int index = 0; index < blocks.size();) {
                CanonicalBlock block = blocks.get(index);
                if (!citable(block, source.page().pageNo(), boilerplate)) {
                    index++;
                    continue;
                }
                if (block.kind() == TextBlockKind.PARAGRAPH
                        && block.displayText().length() > MAX_PARAGRAPH_CHARACTERS) {
                    List<TextRange> ranges = paragraphRanges(block.displayText());
                    for (int rangeIndex = 0; rangeIndex < ranges.size(); rangeIndex++) {
                        TextRange range = ranges.get(rangeIndex);
                        textUnits.add(textSliceUnit(revision, document, source, block,
                                EvidenceUnitType.CONTENT, range, "paragraph:" + rangeIndex));
                    }
                    index++;
                    continue;
                }
                if (block.kind() == TextBlockKind.TABLE) {
                    List<TextRange> lines = nonBlankLineRanges(block.displayText());
                    if (!lines.isEmpty()) {
                        int rowStart = Math.min(block.tableHeaderRowCount(), lines.size());
                        EvidenceUnit header = null;
                        if (rowStart > 0) {
                            TextRange headerRows = new TextRange(lines.get(0).start(), lines.get(rowStart - 1).end());
                            header = textSliceUnit(revision, document, source, block,
                                    EvidenceUnitType.TABLE_HEADER, headerRows, "table-header:" + rowStart);
                            textUnits.add(header);
                            evidenceByBlock.put(source.page().pageNo() + ":" + block.blockId(), header.evidenceId());
                        }
                        for (int groupStart = rowStart; groupStart < lines.size(); groupStart += TABLE_ROWS_PER_GROUP) {
                            int rowEnd = Math.min(lines.size(), groupStart + TABLE_ROWS_PER_GROUP);
                            TextRange rows = new TextRange(lines.get(groupStart).start(), lines.get(rowEnd - 1).end());
                            EvidenceUnit rowGroup = textSliceUnit(revision, document, source, block,
                                    lines.size() == 1 ? EvidenceUnitType.TABLE : EvidenceUnitType.TABLE_ROW_GROUP,
                                    rows, "table-rows:" + groupStart);
                            textUnits.add(rowGroup);
                            if (header != null) {
                                structuralRelations.add(new EvidenceRelation(header.evidenceId(),
                                        rowGroup.evidenceId(), EvidenceRelationType.TABLE_HEADER_FOR, 1.0));
                            }
                        }
                        index++;
                        continue;
                    }
                }
                List<CanonicalBlock> group = new ArrayList<>();
                group.add(block);
                if (block.kind() == TextBlockKind.LIST_ITEM) {
                    int cursor = index + 1;
                    while (cursor < blocks.size() && group.size() < 8
                            && blocks.get(cursor).kind() == TextBlockKind.LIST_ITEM
                            && blocks.get(cursor).textSource() == block.textSource()
                            && sameImplicitListLevel(block, blocks.get(cursor))
                            && citable(blocks.get(cursor), source.page().pageNo(), boilerplate)) {
                        group.add(blocks.get(cursor++));
                    }
                }
                EvidenceUnit unit = textUnit(revision, document, source, group);
                textUnits.add(unit);
                group.forEach(member -> evidenceByBlock.put(source.page().pageNo() + ":" + member.blockId(),
                        unit.evidenceId()));
                index += group.size();
            }
        }
        List<EvidenceUnit> units = new ArrayList<>(textUnits);
        Map<String, EvidenceUnit> textUnitById = new HashMap<>();
        textUnits.forEach(unit -> textUnitById.put(unit.evidenceId(), unit));
        List<EvidenceRelation> relations = sequentialRelations(textUnits);
        relations.addAll(structuralRelations);
        for (VisualCropArtifact crop : visuals.crops().stream()
                .sorted(Comparator.comparingInt((VisualCropArtifact value) -> value.candidate().pageNo())
                        .thenComparing(value -> value.candidate().candidateId())).toList()) {
            String captionId = evidenceByBlock.get(crop.candidate().pageNo() + ":"
                    + crop.candidate().captionBlockId());
            EvidenceSourcePage visualPage = sourceByPage.get(crop.candidate().pageNo());
            DocumentSection fallbackSection = visualPage == null ? null
                    : sectionForVisual(document.sections(), visualPage.page(), crop.candidate());
            String sectionId = captionId == null || textUnitById.get(captionId) == null
                    ? (fallbackSection == null ? null : fallbackSection.sectionId())
                    : textUnitById.get(captionId).sectionId();
            List<EvidenceRegion> regions = new ArrayList<>();
            for (int index = 0; index < crop.candidate().regions().size(); index++) {
                regions.add(new EvidenceRegion(crop.pageId(), index + 1, crop.candidate().regions().get(index),
                        null, null, crop.candidate().candidateId()));
            }
            String id = evidenceId(revision, "visual:" + crop.candidate().candidateId());
            EvidenceUnit visual = new EvidenceUnit(id, crop.pageId(), crop.candidate().pageNo(),
                    sectionId, EvidenceUnitType.VISUAL,
                    EvidenceModality.VISUAL, "VISUAL", null, null, null, crop.artifact(), regions, 1.0);
            units.add(visual);
            if (captionId != null) {
                relations.add(new EvidenceRelation(captionId, id, EvidenceRelationType.CAPTION_OF, 1.0));
            }
        }
        List<SectionHeadingEvidence> headings = document.sections().stream()
                .filter(section -> section.headingBlockId() != null)
                .map(section -> {
                    String evidenceId = evidenceByBlock.get(section.pageStart() + ":" + section.headingBlockId());
                    return evidenceId == null ? null : new SectionHeadingEvidence(
                            section.sectionId(), evidenceId, section.headingBlockId());
                }).filter(Objects::nonNull).toList();
        String evidenceHash = sha256(fingerprint() + ":" + document.structureHash() + ":"
                + units + ":" + relations + ":" + headings);
        return new EvidenceManifest(SCHEMA_VERSION, revision, version, document.structureHash(), fingerprint(),
                evidenceHash, units, relations, headings);
    }

    private static EvidenceUnit textUnit(String revisionId, DocumentStructure structure,
                                         EvidenceSourcePage source, List<CanonicalBlock> blocks) {
        String displayText = String.join("\n", blocks.stream().map(CanonicalBlock::displayText).toList());
        String identity = "text:" + source.page().pageNo() + ":"
                + String.join(",", blocks.stream().map(CanonicalBlock::blockId).toList());
        List<EvidenceRegion> regions = new ArrayList<>();
        int textOffset = 0;
        int ordinal = 1;
        for (CanonicalBlock block : blocks) {
            for (SourceMapSpan span : block.sourceMap()) {
                for (NormalizedBoundingBox box : span.regions()) {
                    regions.add(new EvidenceRegion(source.pageId(), ordinal++, box,
                            textOffset + span.displayStart(), textOffset + span.displayEnd(), block.blockId()));
                }
            }
            textOffset += block.displayText().length() + 1;
        }
        CanonicalBlock first = blocks.get(0);
        DocumentSection section = sectionForBlock(structure.sections(), source.page(), first);
        double quality = blocks.stream().mapToDouble(CanonicalBlock::confidence).min().orElseThrow();
        return new EvidenceUnit(evidenceId(revisionId, identity), source.pageId(), source.page().pageNo(),
                section == null ? null : section.sectionId(), unitType(first.kind()), EvidenceModality.TEXT,
                first.textSource().name(), displayText, sha256(displayText), source.canonicalArtifact(), null,
                regions, quality);
    }

    private static EvidenceUnit textSliceUnit(String revisionId, DocumentStructure structure,
                                              EvidenceSourcePage source, CanonicalBlock block,
                                              EvidenceUnitType unitType, TextRange range, String identitySuffix) {
        String displayText = block.displayText().substring(range.start(), range.end());
        List<EvidenceRegion> regions = new ArrayList<>();
        int ordinal = 1;
        for (SourceMapSpan span : block.sourceMap()) {
            int overlapStart = Math.max(range.start(), span.displayStart());
            int overlapEnd = Math.min(range.end(), span.displayEnd());
            if (overlapStart >= overlapEnd) {
                continue;
            }
            for (NormalizedBoundingBox box : span.regions()) {
                regions.add(new EvidenceRegion(source.pageId(), ordinal++, box,
                        overlapStart - range.start(), overlapEnd - range.start(), block.blockId()));
            }
        }
        if (regions.isEmpty()) {
            for (NormalizedBoundingBox box : block.regions()) {
                regions.add(new EvidenceRegion(source.pageId(), ordinal++, box,
                        0, displayText.length(), block.blockId()));
            }
        }
        DocumentSection section = sectionForBlock(structure.sections(), source.page(), block);
        String identity = "text:" + source.page().pageNo() + ":" + block.blockId() + ":" + identitySuffix
                + ":" + range.start() + ":" + range.end();
        return new EvidenceUnit(evidenceId(revisionId, identity), source.pageId(), source.page().pageNo(),
                section == null ? null : section.sectionId(), unitType, EvidenceModality.TEXT,
                block.textSource().name(), displayText, sha256(displayText), source.canonicalArtifact(), null,
                regions, block.confidence());
    }

    private static List<TextRange> paragraphRanges(String text) {
        List<TextRange> ranges = new ArrayList<>();
        int start = skipWhitespaceForward(text, 0, text.length());
        while (start < text.length()) {
            int limit = Math.min(text.length(), start + MAX_PARAGRAPH_CHARACTERS);
            // Keep a hard fallback split on a Unicode code-point boundary.
            if (limit < text.length() && Character.isHighSurrogate(text.charAt(limit - 1))
                    && Character.isLowSurrogate(text.charAt(limit))) {
                limit--;
            }
            int end = limit;
            if (limit < text.length()) {
                int boundary = lastSentenceBoundary(text, start, limit);
                if (boundary > start) {
                    end = boundary;
                }
            }
            int trimmedEnd = skipWhitespaceBackward(text, start, end);
            if (trimmedEnd > start) {
                ranges.add(new TextRange(start, trimmedEnd));
            }
            start = skipWhitespaceForward(text, end, text.length());
        }
        return List.copyOf(ranges);
    }

    private static int lastSentenceBoundary(String text, int start, int limit) {
        for (int index = limit - 1; index >= start; index--) {
            char value = text.charAt(index);
            if (value == '.' || value == '!' || value == '?' || value == '。'
                    || value == '！' || value == '？' || value == '\n') {
                return index + 1;
            }
        }
        for (int index = limit - 1; index > start; index--) {
            if (Character.isWhitespace(text.charAt(index))) {
                return index + 1;
            }
        }
        return limit;
    }

    private static List<TextRange> nonBlankLineRanges(String text) {
        List<TextRange> ranges = new ArrayList<>();
        int lineStart = 0;
        for (int index = 0; index <= text.length(); index++) {
            if (index < text.length() && text.charAt(index) != '\n') {
                continue;
            }
            int start = skipWhitespaceForward(text, lineStart, index);
            int end = skipWhitespaceBackward(text, start, index);
            if (start < end) {
                ranges.add(new TextRange(start, end));
            }
            lineStart = index + 1;
        }
        return List.copyOf(ranges);
    }

    private static int skipWhitespaceForward(String text, int start, int limit) {
        int cursor = start;
        while (cursor < limit && Character.isWhitespace(text.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static int skipWhitespaceBackward(String text, int start, int end) {
        int cursor = end;
        while (cursor > start && Character.isWhitespace(text.charAt(cursor - 1))) {
            cursor--;
        }
        return cursor;
    }

    private static List<EvidenceRelation> sequentialRelations(List<EvidenceUnit> units) {
        List<EvidenceRelation> relations = new ArrayList<>();
        for (int index = 1; index < units.size(); index++) {
            EvidenceUnit previous = units.get(index - 1);
            EvidenceUnit next = units.get(index);
            if (previous.sectionId() == null || !previous.sectionId().equals(next.sectionId())) {
                continue;
            }
            relations.add(new EvidenceRelation(previous.evidenceId(), next.evidenceId(),
                    EvidenceRelationType.NEXT_IN_SECTION, 1.0));
            relations.add(new EvidenceRelation(next.evidenceId(), previous.evidenceId(),
                    EvidenceRelationType.PREVIOUS_IN_SECTION, 1.0));
        }
        return relations;
    }

    private static boolean citable(CanonicalBlock block, int pageNo, Set<String> boilerplate) {
        return !block.displayText().isBlank() && block.kind() != TextBlockKind.VISUAL
                && !boilerplate.contains(pageNo + ":" + block.blockId());
    }

    private static boolean sameImplicitListLevel(CanonicalBlock first, CanonicalBlock candidate) {
        double firstIndent = first.regions().stream().mapToDouble(NormalizedBoundingBox::x1).min().orElse(0);
        double candidateIndent = candidate.regions().stream().mapToDouble(NormalizedBoundingBox::x1).min().orElse(0);
        return Math.abs(firstIndent - candidateIndent) <= 0.02;
    }

    private static EvidenceUnitType unitType(TextBlockKind kind) {
        return switch (kind) {
            case HEADING -> EvidenceUnitType.HEADING;
            case LIST_ITEM -> EvidenceUnitType.LIST;
            case TABLE -> EvidenceUnitType.TABLE;
            case CAPTION -> EvidenceUnitType.CAPTION;
            case HEADER, FOOTER -> EvidenceUnitType.FOOTNOTE;
            case PARAGRAPH -> EvidenceUnitType.CONTENT;
            case VISUAL -> throw new IllegalArgumentException("visual blocks require a pinned crop");
        };
    }

    private static DocumentSection sectionForBlock(List<DocumentSection> sections, CanonicalPage page,
                                                   CanonicalBlock block) {
        Map<String, Integer> headingOrder = new HashMap<>();
        page.blocks().stream().filter(candidate -> candidate.kind() == TextBlockKind.HEADING)
                .forEach(candidate -> headingOrder.put(candidate.blockId(), candidate.readingOrder()));
        Optional<DocumentSection> samePageHeading = sections.stream()
                .filter(section -> section.pageStart() == page.pageNo() && section.pageEnd() >= page.pageNo())
                .filter(section -> section.headingBlockId() != null)
                .filter(section -> headingOrder.getOrDefault(section.headingBlockId(), Integer.MAX_VALUE)
                        <= block.readingOrder())
                .max(Comparator.comparingInt(section -> headingOrder.get(section.headingBlockId())));
        return samePageHeading.orElseGet(() -> sections.stream()
                .filter(section -> section.headingBlockId() == null
                        && section.pageStart() <= page.pageNo() && section.pageEnd() >= page.pageNo())
                .findFirst().orElseGet(() -> sections.stream()
                        .filter(section -> section.pageStart() < page.pageNo()
                                && section.pageEnd() >= page.pageNo())
                        .max(Comparator.comparingInt(DocumentSection::pageStart)
                                .thenComparingInt(DocumentSection::level)
                                .thenComparingInt(DocumentSection::ordinal)).orElse(null)));
    }

    private static DocumentSection sectionForVisual(List<DocumentSection> sections, CanonicalPage page,
                                                     VisualCandidate visual) {
        double visualTop = visual.regions().stream().mapToDouble(NormalizedBoundingBox::y1).min().orElse(0);
        Map<String, Double> headingTop = new HashMap<>();
        page.blocks().stream().filter(block -> block.kind() == TextBlockKind.HEADING)
                .forEach(block -> headingTop.put(block.blockId(), block.regions().stream()
                        .mapToDouble(NormalizedBoundingBox::y1).min().orElse(1)));
        Optional<DocumentSection> samePageHeading = sections.stream()
                .filter(section -> section.pageStart() == page.pageNo() && section.pageEnd() >= page.pageNo())
                .filter(section -> section.headingBlockId() != null)
                .filter(section -> headingTop.getOrDefault(section.headingBlockId(), Double.POSITIVE_INFINITY)
                        <= visualTop)
                .max(Comparator.comparingDouble(section -> headingTop.get(section.headingBlockId())));
        return samePageHeading.orElseGet(() -> sections.stream()
                .filter(section -> section.headingBlockId() == null
                        && section.pageStart() <= page.pageNo() && section.pageEnd() >= page.pageNo())
                .findFirst().orElseGet(() -> sections.stream()
                        .filter(section -> section.pageStart() < page.pageNo()
                                && section.pageEnd() >= page.pageNo())
                        .max(Comparator.comparingInt(DocumentSection::pageStart)
                                .thenComparingInt(DocumentSection::level)
                                .thenComparingInt(DocumentSection::ordinal)).orElse(null)));
    }

    private static String evidenceId(String revisionId, String sourceIdentity) {
        return "evi_" + sha256(revisionId + ":" + sourceIdentity).substring(0, 40);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private record TextRange(int start, int end) {
        private TextRange {
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("text evidence range is invalid");
            }
        }
    }
}
