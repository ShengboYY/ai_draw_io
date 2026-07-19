package org.zipp.ai.domain.retrieval.projection;

import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.retrieval.model.valobj.ChunkEvidenceRole;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalChunkType;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalIndexMode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.BreakIterator;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds deterministic search projections while keeping Evidence as the citation boundary. */
public final class RetrievalChunkBuilder {

    private static final String SCHEMA_VERSION = "retrieval-projection-v1";
    private static final Pattern QUOTED_PHRASE = Pattern.compile(
            "(?:\"([^\"]{2,120})\"|“([^”]{2,120})”|‘([^’]{2,120})’|《([^》]{2,120})》)");
    private static final Pattern URL_HOST = Pattern.compile(
            "(?i)https?://([a-z0-9.-]+\\.[a-z]{2,})(?:[/:?#][^\\s]*)?");
    private static final Pattern VERSION = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])v?\\d+(?:\\.\\d+){1,3}(?:[-+][a-z0-9.-]+)?(?![\\p{L}\\p{N}])");
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}(?!\\d)");
    private static final Pattern CURRENCY = Pattern.compile(
            "(?i)(?:[$€£¥￥]\\s?\\d+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?\\s?(?:USD|CNY|RMB|EUR|GBP|JPY|AUD))");
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?\\s?(?:%|ms|s|sec|KB|MB|GB|TB|px|dpi|mm|cm|m|km|kg|g)(?![\\p{L}\\p{N}])");
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)?(?![\\p{L}\\p{N}])");
    private static final Pattern ACRONYM = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])[A-Z]{2,}[0-9]*(?![\\p{L}\\p{N}_])");
    private static final Pattern ALPHANUMERIC_ID = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])[A-Za-z0-9][A-Za-z0-9_-]{2,}(?![\\p{L}\\p{N}_])");
    private static final Pattern SNAKE_CASE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])[A-Za-z][A-Za-z0-9]*(?:_[A-Za-z0-9]+)+(?![\\p{L}\\p{N}_])");
    private static final Pattern CAMEL_CASE = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])(?:[a-z]+(?:[A-Z][A-Za-z0-9]*)+|[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+)(?![\\p{L}\\p{N}_])");
    private static final Pattern QUALIFIED_IDENTIFIER = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])[A-Za-z_$][A-Za-z0-9_$]*(?:[.:/][A-Za-z_$][A-Za-z0-9_$]*(?:\\(\\))?)+(?![\\p{L}\\p{N}_])");
    private static final Pattern SYNTHETIC_TYPE_LINE = Pattern.compile(
            "(?m)^\\[内容类型] [^\\r\\n]*(?:\\R|$)");
    private static final Pattern PROJECTION_LABEL = Pattern.compile(
            "(?m)^\\[(?:章节|正文|表头|内容|文档结构)]\\s*");
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "(?iu)^page\\s*\\d{1,6}$|^第?\\s*\\d{1,6}\\s*页$");
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\d{1,6}$");
    private static final int CONTENT_LIMIT = 420;
    private static final int LIST_TABLE_LIMIT = 400;
    private static final int CAPTION_LIMIT = 320;
    private static final int VISUAL_LIMIT = 420;
    private static final int PARENT_LIMIT = 900;

    private final RetrievalTokenCounter tokenCounter;

    public RetrievalChunkBuilder(RetrievalTokenCounter tokenCounter) {
        this.tokenCounter = Objects.requireNonNull(tokenCounter, "tokenCounter");
    }

    public String fingerprint() {
        return SCHEMA_VERSION + ":leaf-by-evidence:sentence-safe-split:parent-neighbor-max900:"
                + "section-bridge-min3-or500:aux-hard20pct:extractive-profile:lexical-v2:tokenizer="
                + tokenCounter.fingerprint();
    }

    public RetrievalProjectionManifest build(EvidenceManifest evidence) {
        EvidenceManifest source = Objects.requireNonNull(evidence, "evidence");
        Map<String, EvidenceUnit> byId = new HashMap<>();
        source.units().forEach(unit -> byId.put(unit.evidenceId(), unit));
        Map<String, EvidenceUnit> headingBySection = new LinkedHashMap<>();
        source.sectionHeadings().forEach(heading -> headingBySection.put(heading.sectionId(),
                byId.get(heading.evidenceId())));
        Map<String, EvidenceUnit> tableHeaderByRow = relatedEvidence(source, byId,
                EvidenceRelationType.TABLE_HEADER_FOR);
        Map<String, EvidenceUnit> captionByVisual = relatedEvidence(source, byId,
                EvidenceRelationType.CAPTION_OF);

        List<RetrievalChunkProjection> leaves = new ArrayList<>();
        for (EvidenceUnit unit : source.units()) {
            if (unit.unitType() == EvidenceUnitType.HEADING
                    || unit.unitType() == EvidenceUnitType.TABLE_HEADER) {
                continue;
            }
            RetrievalChunkType type = chunkType(unit.unitType());
            EvidenceUnit heading = unit.sectionId() == null ? null : headingBySection.get(unit.sectionId());
            EvidenceUnit context = unit.modality() == EvidenceModality.VISUAL
                    ? captionByVisual.get(unit.evidenceId()) : tableHeaderByRow.get(unit.evidenceId());
            String body = unit.displayText() == null
                    ? context == null ? "Visual evidence on page " + unit.pageNo() : context.displayText()
                    : unit.displayText();
            String prefix = prefix(heading == null ? null : heading.displayText(), type,
                    context == null || unit.modality() == EvidenceModality.VISUAL
                    ? null : context.displayText());
            int limit = hardLimit(type);
            List<TextRange> ranges = unit.displayText() == null
                    ? List.of(new TextRange(0, body.length())) : split(body, prefix, limit);
            for (TextRange range : ranges) {
                TextRange exactRange = trimRange(body, range);
                String fragment = body.substring(exactRange.start(), exactRange.end());
                String retrievalText = prefix + fragment;
                List<RetrievalEvidenceMapping> mappings = new ArrayList<>();
                mappings.add(new RetrievalEvidenceMapping(unit.evidenceId(), ChunkEvidenceRole.PRIMARY, 0,
                        ranges.size() == 1 || unit.displayText() == null ? null : exactRange.start(),
                        ranges.size() == 1 || unit.displayText() == null ? null : exactRange.end()));
                if (context != null) {
                    mappings.add(new RetrievalEvidenceMapping(context.evidenceId(),
                            unit.modality() == EvidenceModality.VISUAL
                                    ? ChunkEvidenceRole.CAPTION : ChunkEvidenceRole.HEADER,
                            1, null, null));
                }
                if (heading != null && (context == null || !heading.evidenceId().equals(context.evidenceId()))) {
                    mappings.add(new RetrievalEvidenceMapping(heading.evidenceId(),
                            ChunkEvidenceRole.HEADER, mappings.size(), null, null));
                }
                RetrievalIndexMode mode = indexMode(unit, context, body);
                int ordinal = leaves.size() + 1;
                leaves.add(chunk(source.revisionId(), unit.pageId(), unit.sectionId(), type, unit.modality(),
                        mode, retrievalText, null, unit.quality(), ordinal, mappings));
            }
        }
        leaves = mergeShortLeaves(source.revisionId(), leaves);
        leaves = withParentContexts(leaves);
        List<RetrievalChunkProjection> chunks = new ArrayList<>(leaves);
        List<RetrievalChunkProjection> searchableLeaves = leaves.stream()
                .filter(chunk -> chunk.indexMode() != RetrievalIndexMode.UNSEARCHABLE).toList();
        chunks.addAll(auxiliaryChunks(source, searchableLeaves, headingBySection, byId));
        List<LexicalProjection> lexical = chunks.stream()
                .filter(chunk -> chunk.indexMode() != RetrievalIndexMode.UNSEARCHABLE)
                .map(this::lexicalProjection).toList();
        String projectionHash = sha256(fingerprint() + ":" + source.evidenceHash() + ":" + chunks + ":" + lexical);
        return new RetrievalProjectionManifest(SCHEMA_VERSION, source.revisionId(), source.versionId(),
                source.evidenceHash(), fingerprint(), chunks, lexical, projectionHash);
    }

    private List<RetrievalChunkProjection> mergeShortLeaves(String revisionId,
                                                             List<RetrievalChunkProjection> leaves) {
        List<RetrievalChunkProjection> merged = new ArrayList<>();
        for (int index = 0; index < leaves.size();) {
            RetrievalChunkProjection current = leaves.get(index);
            int cursor = index + 1;
            while (current.tokenCount() < minimumTokens(current.chunkType()) && cursor < leaves.size()) {
                RetrievalChunkProjection next = leaves.get(cursor);
                if (!canMerge(current, next)) {
                    break;
                }
                current = merge(revisionId, current, next, merged.size() + 1);
                cursor++;
            }
            // A short final block can safely join its predecessor even when that predecessor was already large.
            if (current.tokenCount() < minimumTokens(current.chunkType()) && !merged.isEmpty()
                    && canMerge(merged.get(merged.size() - 1), current)) {
                RetrievalChunkProjection previous = merged.remove(merged.size() - 1);
                current = merge(revisionId, previous, current, merged.size() + 1);
            }
            if (current.structuralOrdinal() != merged.size() + 1) {
                current = new RetrievalChunkProjection(current.chunkId(), current.pageId(), current.sectionId(),
                        current.chunkType(), current.modality(), current.languagePrimary(), current.citable(),
                        current.indexMode(), current.retrievalText(), current.retrievalTextSha256(), null, List.of(),
                        current.tokenCount(), current.quality(), merged.size() + 1, current.evidenceMappings());
            }
            merged.add(current);
            index = cursor;
        }
        return List.copyOf(merged);
    }

    private boolean canMerge(RetrievalChunkProjection first, RetrievalChunkProjection second) {
        return mergeCompatible(first, second)
                && tokenCounter.count(first.retrievalText() + "\n\n" + second.retrievalText())
                <= hardLimit(first.chunkType());
    }

    private RetrievalChunkProjection merge(String revisionId, RetrievalChunkProjection first,
                                            RetrievalChunkProjection second, int ordinal) {
        List<RetrievalEvidenceMapping> mappings = new ArrayList<>(first.evidenceMappings());
        mappings.addAll(second.evidenceMappings());
        return chunk(revisionId, first.pageId(), first.sectionId(), first.chunkType(), first.modality(),
                first.indexMode(), first.retrievalText() + "\n\n" + second.retrievalText(), null,
                Math.min(first.quality(), second.quality()), ordinal, deduplicateMappings(mappings));
    }

    private static boolean mergeCompatible(RetrievalChunkProjection first, RetrievalChunkProjection second) {
        if (!(first.chunkType() == RetrievalChunkType.CONTENT
                || first.chunkType() == RetrievalChunkType.LIST_GROUP)
                || first.chunkType() != second.chunkType() || first.modality() != second.modality()
                || first.indexMode() != second.indexMode() || !Objects.equals(first.pageId(), second.pageId())
                || !Objects.equals(first.sectionId(), second.sectionId())) {
            return false;
        }
        Set<String> firstPrimary = new HashSet<>(first.evidenceMappings().stream()
                .filter(mapping -> mapping.role() == ChunkEvidenceRole.PRIMARY)
                .map(RetrievalEvidenceMapping::evidenceId).toList());
        return second.evidenceMappings().stream()
                .filter(mapping -> mapping.role() == ChunkEvidenceRole.PRIMARY)
                .noneMatch(mapping -> firstPrimary.contains(mapping.evidenceId()));
    }

    private static List<RetrievalEvidenceMapping> deduplicateMappings(List<RetrievalEvidenceMapping> mappings) {
        LinkedHashMap<String, RetrievalEvidenceMapping> byEvidence = new LinkedHashMap<>();
        mappings.forEach(mapping -> byEvidence.putIfAbsent(mapping.evidenceId(), mapping));
        List<RetrievalEvidenceMapping> result = new ArrayList<>();
        int ordinal = 0;
        for (RetrievalEvidenceMapping mapping : byEvidence.values()) {
            result.add(new RetrievalEvidenceMapping(mapping.evidenceId(), mapping.role(), ordinal++,
                    mapping.charStart(), mapping.charEnd()));
        }
        return List.copyOf(result);
    }

    private List<RetrievalChunkProjection> withParentContexts(List<RetrievalChunkProjection> leaves) {
        List<RetrievalChunkProjection> result = new ArrayList<>();
        for (int index = 0; index < leaves.size(); index++) {
            RetrievalChunkProjection current = leaves.get(index);
            String context = current.retrievalText();
            List<RetrievalChunkProjection> contextChunks = new ArrayList<>();
            contextChunks.add(current);
            if (index > 0 && sameContext(current, leaves.get(index - 1))) {
                String candidate = leaves.get(index - 1).retrievalText() + "\n\n" + context;
                if (tokenCounter.count(candidate) <= PARENT_LIMIT) {
                    context = candidate;
                    contextChunks.add(0, leaves.get(index - 1));
                }
            }
            if (index + 1 < leaves.size() && sameContext(current, leaves.get(index + 1))) {
                String candidate = context + "\n\n" + leaves.get(index + 1).retrievalText();
                if (tokenCounter.count(candidate) <= PARENT_LIMIT) {
                    context = candidate;
                    contextChunks.add(leaves.get(index + 1));
                }
            }
            List<String> parentEvidence = contextChunks.stream()
                    .flatMap(chunk -> chunk.evidenceMappings().stream())
                    .map(RetrievalEvidenceMapping::evidenceId).distinct().toList();
            result.add(new RetrievalChunkProjection(current.chunkId(), current.pageId(), current.sectionId(),
                    current.chunkType(), current.modality(), current.languagePrimary(), current.citable(),
                    current.indexMode(), current.retrievalText(), current.retrievalTextSha256(), context,
                    parentEvidence, current.tokenCount(), current.quality(), current.structuralOrdinal(),
                    current.evidenceMappings()));
        }
        return List.copyOf(result);
    }

    private List<RetrievalChunkProjection> auxiliaryChunks(EvidenceManifest source,
                                                            List<RetrievalChunkProjection> leaves,
                                                            Map<String, EvidenceUnit> headingBySection,
                                                            Map<String, EvidenceUnit> evidenceById) {
        if (leaves.isEmpty()) {
            return List.of();
        }
        int maximum = (int) Math.floor(leaves.size() * 0.20);
        if (maximum == 0) {
            return List.of();
        }
        List<RetrievalChunkProjection> auxiliary = new ArrayList<>();
        Map<String, List<RetrievalChunkProjection>> bySection = new LinkedHashMap<>();
        leaves.stream().filter(chunk -> chunk.sectionId() != null)
                .forEach(chunk -> bySection.computeIfAbsent(chunk.sectionId(), ignored -> new ArrayList<>()).add(chunk));
        for (var entry : bySection.entrySet()) {
            if (auxiliary.size() >= maximum - 1) {
                break;
            }
            int tokens = entry.getValue().stream().mapToInt(RetrievalChunkProjection::tokenCount).sum();
            if (entry.getValue().size() < 3 && tokens < 500) {
                continue;
            }
            EvidenceUnit heading = headingBySection.get(entry.getKey());
            AnchorSelection prefix = heading == null
                    ? new AnchorSelection("[章节] " + entry.getKey() + "\n[内容] ", List.of())
                    : sourcePrefix("[章节] ", List.of(heading), "\n[内容] ", 100);
            AnchorSelection selection = selectAnchors(prefix, entry.getValue(), 380, evidenceById);
            auxiliary.add(auxiliary(source.revisionId(), RetrievalChunkType.SECTION_BRIDGE, entry.getKey(),
                    selection, leaves.size() + auxiliary.size() + 1));
        }
        if (auxiliary.size() < maximum) {
            List<EvidenceUnit> outlineHeadings = headingBySection.values().stream().filter(Objects::nonNull)
                    .filter(value -> !value.displayText().isBlank()).distinct().limit(20).toList();
            AnchorSelection prefix = outlineHeadings.isEmpty()
                    ? new AnchorSelection("[文档结构] Document\n[内容] ", List.of())
                    : sourcePrefix("[文档结构] ", outlineHeadings, "\n[内容] ", 100);
            AnchorSelection selection = selectAnchors(prefix, leaves, 380, evidenceById);
            auxiliary.add(auxiliary(source.revisionId(), RetrievalChunkType.DOCUMENT_PROFILE, null,
                    selection, leaves.size() + auxiliary.size() + 1));
        }
        return List.copyOf(auxiliary);
    }

    private RetrievalChunkProjection auxiliary(String revisionId, RetrievalChunkType type, String sectionId,
                                                AnchorSelection selection,
                                                int ordinal) {
        List<String> representativeEvidence = selection.evidenceIds().stream().distinct().toList();
        if (representativeEvidence.isEmpty()) {
            throw new IllegalArgumentException("auxiliary retrieval text must retain source Evidence");
        }
        List<RetrievalEvidenceMapping> mappings = new ArrayList<>();
        for (int index = 0; index < representativeEvidence.size(); index++) {
            mappings.add(new RetrievalEvidenceMapping(representativeEvidence.get(index),
                    ChunkEvidenceRole.REPRESENTATIVE, index, null, null));
        }
        return chunk(revisionId, null, sectionId, type, EvidenceModality.TEXT,
                RetrievalIndexMode.DENSE_AND_LEXICAL, selection.text(), null, 1.0, ordinal, mappings);
    }

    private RetrievalChunkProjection chunk(String revisionId, String pageId, String sectionId,
                                            RetrievalChunkType type, EvidenceModality modality,
                                            RetrievalIndexMode mode, String text, String parent,
                                            double quality, int ordinal,
                                            List<RetrievalEvidenceMapping> mappings) {
        int tokens = tokenCounter.count(text);
        if (tokens < 1 || tokens > hardLimit(type)) {
            throw new IllegalArgumentException("retrieval text exceeds its exact token budget");
        }
        String mappingIdentity = mappings.stream().map(mapping -> mapping.evidenceId() + ":"
                + mapping.role() + ":" + mapping.ordinal() + ":"
                + mapping.charStart() + ":" + mapping.charEnd()).sorted().toList().toString();
        String id = "rc_" + sha256(revisionId + ":" + type + ":" + mappingIdentity + ":" + text).substring(0, 40);
        return new RetrievalChunkProjection(id, pageId, sectionId, type, modality,
                language(withoutProjectionLabels(text)),
                type.allowsCitation(), mode, text, sha256(text), parent, List.of(), tokens, quality, ordinal, mappings);
    }

    private LexicalProjection lexicalProjection(RetrievalChunkProjection chunk) {
        String text = chunk.retrievalText();
        // Projection labels are routing metadata; only their source-backed values open a language lane.
        String lexicalSignals = withoutProjectionLabels(text);
        boolean word = lexicalSignals.codePoints().anyMatch(value -> Character.UnicodeScript.of(value)
                == Character.UnicodeScript.LATIN || Character.isDigit(value));
        boolean cjk = lexicalSignals.codePoints().anyMatch(RetrievalChunkBuilder::isHan);
        LinkedHashMap<String, LexicalProjection.ExactTerm> terms = new LinkedHashMap<>();
        addCapturedTerms(terms, QUOTED_PHRASE.matcher(lexicalSignals), "QUOTED");
        addCapturedTerms(terms, URL_HOST.matcher(lexicalSignals), "URL_HOST");
        addTerms(terms, VERSION.matcher(lexicalSignals), "VERSION");
        addTerms(terms, DATE.matcher(lexicalSignals), "DATE");
        addTerms(terms, CURRENCY.matcher(lexicalSignals), "CURRENCY");
        addTerms(terms, NUMBER_WITH_UNIT.matcher(lexicalSignals), "NUMBER_UNIT");
        addTerms(terms, ACRONYM.matcher(lexicalSignals), "ACRONYM");
        addStrongIdentifierTerms(terms, ALPHANUMERIC_ID.matcher(lexicalSignals));
        addTerms(terms, SNAKE_CASE.matcher(lexicalSignals), "IDENTIFIER");
        addTerms(terms, CAMEL_CASE.matcher(lexicalSignals), "IDENTIFIER");
        addTerms(terms, QUALIFIED_IDENTIFIER.matcher(lexicalSignals), "IDENTIFIER");
        addTerms(terms, NUMBER.matcher(lexicalSignals), "NUMBER");
        return new LexicalProjection(chunk.chunkId(), word ? text : null, cjk ? text : null,
                List.copyOf(terms.values()));
    }

    private static void addCapturedTerms(Map<String, LexicalProjection.ExactTerm> target,
                                         Matcher matcher, String type) {
        while (matcher.find() && target.size() < 64) {
            String value = null;
            for (int group = 1; group <= matcher.groupCount() && value == null; group++) {
                value = matcher.group(group);
            }
            addTerm(target, value, type);
        }
    }

    private static void addStrongIdentifierTerms(Map<String, LexicalProjection.ExactTerm> target,
                                                  Matcher matcher) {
        while (matcher.find() && target.size() < 64) {
            String value = matcher.group();
            boolean hasLetter = value.codePoints().anyMatch(Character::isLetter);
            boolean hasDigit = value.codePoints().anyMatch(Character::isDigit);
            if (hasLetter && hasDigit) {
                addTerm(target, value, "IDENTIFIER");
            }
        }
    }

    private static void addTerms(Map<String, LexicalProjection.ExactTerm> target, Matcher matcher, String type) {
        while (matcher.find() && target.size() < 64) {
            addTerm(target, matcher.group(), type);
        }
    }

    private static void addTerm(Map<String, LexicalProjection.ExactTerm> target, String value, String type) {
        if (value == null) {
            return;
        }
        String normalized = value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty() && normalized.length() <= 255 && target.size() < 64) {
            target.putIfAbsent(type + ":" + normalized, new LexicalProjection.ExactTerm(normalized, type));
        }
    }

    private List<TextRange> split(String body, String prefix, int limit) {
        if (tokenCounter.count(prefix + body) <= limit) {
            return List.of(new TextRange(0, body.length()));
        }
        List<TextRange> ranges = new ArrayList<>();
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.ROOT);
        iterator.setText(body);
        int start = 0;
        while (start < body.length()) {
            int end = start;
            for (int boundary = iterator.following(start); boundary != BreakIterator.DONE;
                 boundary = iterator.next()) {
                if (tokenCounter.count(prefix + body.substring(start, boundary)) > limit) {
                    break;
                }
                end = boundary;
            }
            if (end == start) {
                end = largestFittingBoundary(body, start, prefix, limit);
            }
            ranges.add(new TextRange(start, end));
            start = end;
        }
        return List.copyOf(ranges);
    }

    private int largestFittingBoundary(String text, int start, String prefix, int limit) {
        int end = start;
        for (int cursor = start; cursor < text.length();) {
            int next = text.offsetByCodePoints(cursor, 1);
            if (tokenCounter.count(prefix + text.substring(start, next)) > limit) {
                break;
            }
            end = next;
            cursor = next;
        }
        if (end == start) {
            throw new IllegalArgumentException("retrieval prefix leaves no token budget for Evidence");
        }
        int whitespace = end;
        while (whitespace > start && !Character.isWhitespace(text.charAt(whitespace - 1))) {
            whitespace--;
        }
        return whitespace > start + (end - start) / 2 ? whitespace : end;
    }

    private AnchorSelection sourcePrefix(String label, List<EvidenceUnit> evidence,
                                         String suffix, int limit) {
        String text = label;
        List<String> evidenceIds = new ArrayList<>();
        for (EvidenceUnit unit : evidence) {
            String separator = evidenceIds.isEmpty() ? "" : "; ";
            String candidate = text + separator + unit.displayText() + suffix;
            if (tokenCounter.count(candidate) <= limit) {
                text += separator + unit.displayText();
                evidenceIds.add(unit.evidenceId());
                continue;
            }
            String fragment = largestSourcePrefix(text + separator, unit.displayText(), suffix, limit);
            if (!fragment.isEmpty()) {
                text += separator + fragment;
                evidenceIds.add(unit.evidenceId());
            }
            break;
        }
        return new AnchorSelection(text + suffix, evidenceIds);
    }

    private String largestSourcePrefix(String prefix, String source, String suffix, int limit) {
        int end = 0;
        for (int cursor = 0; cursor < source.length();) {
            int next = source.offsetByCodePoints(cursor, 1);
            if (tokenCounter.count(prefix + source.substring(0, next) + suffix) > limit) {
                break;
            }
            end = next;
            cursor = next;
        }
        return source.substring(0, end).stripTrailing();
    }

    private AnchorSelection selectAnchors(AnchorSelection prefix, List<RetrievalChunkProjection> chunks,
                                          int limit, Map<String, EvidenceUnit> evidenceById) {
        String text = prefix.text();
        List<String> evidenceIds = new ArrayList<>(prefix.evidenceIds());
        boolean includedChunk = false;
        for (RetrievalChunkProjection chunk : chunks) {
            String candidate = text + (includedChunk ? "\n" : "") + chunk.retrievalText();
            if (tokenCounter.count(candidate) > limit) {
                if (!includedChunk) {
                    RetrievalEvidenceMapping mapping = fallbackMapping(chunk, evidenceById);
                    EvidenceUnit evidence = evidenceById.get(mapping.evidenceId());
                    String source = mappedSource(evidence, mapping);
                    String fragment = largestSourcePrefix(text, source, "", limit);
                    if (fragment.isEmpty()) {
                        throw new IllegalArgumentException("auxiliary prefix leaves no source token budget");
                    }
                    text += fragment;
                    evidenceIds.add(mapping.evidenceId());
                    // A caption excerpt is also a routing anchor for its visual primary Evidence.
                    if (chunk.modality() == EvidenceModality.VISUAL) {
                        chunk.evidenceMappings().stream()
                                .filter(candidateMapping -> candidateMapping.role() == ChunkEvidenceRole.PRIMARY)
                                .map(RetrievalEvidenceMapping::evidenceId).forEach(evidenceIds::add);
                    }
                }
                break;
            }
            text = candidate;
            includedChunk = true;
            chunk.evidenceMappings().stream()
                    .map(RetrievalEvidenceMapping::evidenceId).forEach(evidenceIds::add);
        }
        return new AnchorSelection(text, evidenceIds);
    }

    private static RetrievalEvidenceMapping fallbackMapping(RetrievalChunkProjection chunk,
                                                             Map<String, EvidenceUnit> evidenceById) {
        List<ChunkEvidenceRole> preference = chunk.modality() == EvidenceModality.VISUAL
                ? List.of(ChunkEvidenceRole.CAPTION, ChunkEvidenceRole.PRIMARY, ChunkEvidenceRole.HEADER)
                : List.of(ChunkEvidenceRole.PRIMARY, ChunkEvidenceRole.HEADER, ChunkEvidenceRole.CAPTION);
        for (ChunkEvidenceRole role : preference) {
            for (RetrievalEvidenceMapping mapping : chunk.evidenceMappings()) {
                EvidenceUnit evidence = evidenceById.get(mapping.evidenceId());
                if (mapping.role() == role && evidence != null && evidence.displayText() != null
                        && !mappedSource(evidence, mapping).isBlank()) {
                    return mapping;
                }
            }
        }
        throw new IllegalArgumentException("searchable retrieval chunk has no source-backed anchor");
    }

    private static String mappedSource(EvidenceUnit evidence, RetrievalEvidenceMapping mapping) {
        String source = evidence.displayText();
        return mapping.charStart() == null ? source.strip()
                : source.substring(mapping.charStart(), mapping.charEnd()).strip();
    }

    private String bounded(String text, int limit) {
        return tokenCounter.count(text) <= limit ? text : text.substring(0,
                largestFittingBoundary(text, 0, "", limit)).strip();
    }

    private static Map<String, EvidenceUnit> relatedEvidence(EvidenceManifest source,
                                                              Map<String, EvidenceUnit> byId,
                                                              EvidenceRelationType type) {
        Map<String, EvidenceUnit> result = new HashMap<>();
        source.relations().stream().filter(relation -> relation.relationType() == type)
                .forEach(relation -> result.put(relation.toEvidenceId(), byId.get(relation.fromEvidenceId())));
        return result;
    }

    private static RetrievalChunkType chunkType(EvidenceUnitType type) {
        return switch (type) {
            case LIST -> RetrievalChunkType.LIST_GROUP;
            case TABLE, TABLE_ROW_GROUP -> RetrievalChunkType.TABLE_ROW_GROUP;
            case CAPTION -> RetrievalChunkType.CAPTION_CONTEXT;
            case VISUAL -> RetrievalChunkType.VISUAL_DESCRIPTION;
            case CONTENT, FOOTNOTE -> RetrievalChunkType.CONTENT;
            case HEADING, TABLE_HEADER -> throw new IllegalArgumentException("auxiliary Evidence is not a leaf chunk");
        };
    }

    private static int hardLimit(RetrievalChunkType type) {
        return switch (type) {
            case CONTENT -> CONTENT_LIMIT;
            case VISUAL_DESCRIPTION -> VISUAL_LIMIT;
            case LIST_GROUP, TABLE_ROW_GROUP -> LIST_TABLE_LIMIT;
            case CAPTION_CONTEXT -> CAPTION_LIMIT;
            case SECTION_BRIDGE, DOCUMENT_PROFILE -> 380;
        };
    }

    private static int minimumTokens(RetrievalChunkType type) {
        return switch (type) {
            case CONTENT -> 80;
            case LIST_GROUP, VISUAL_DESCRIPTION -> 50;
            case TABLE_ROW_GROUP -> 40;
            case CAPTION_CONTEXT -> 30;
            case SECTION_BRIDGE, DOCUMENT_PROFILE -> 80;
        };
    }

    private static RetrievalIndexMode indexMode(EvidenceUnit unit, EvidenceUnit context, String retrievalBody) {
        double quality = context == null ? unit.quality() : Math.min(unit.quality(), context.quality());
        if (unit.modality() == EvidenceModality.VISUAL && context == null || quality < 0.35
                || "OCR".equals(unit.sourceChannel()) && quality < 0.45
                || lowInformation(unit, retrievalBody)) {
            return RetrievalIndexMode.UNSEARCHABLE;
        }
        return RetrievalIndexMode.DENSE_AND_LEXICAL;
    }

    private static boolean lowInformation(EvidenceUnit unit, String text) {
        String normalized = Objects.requireNonNullElse(text, "").strip();
        boolean footerPageNumber = unit.unitType() == EvidenceUnitType.FOOTNOTE
                && BARE_NUMBER.matcher(normalized).matches()
                && unit.regions().stream().allMatch(region -> region.boundingBox().y1() >= 0.85);
        if (normalized.isEmpty() || PAGE_NUMBER.matcher(normalized).matches() || footerPageNumber) {
            return true;
        }
        long informationCharacters = normalized.codePoints()
                .filter(value -> Character.isLetterOrDigit(value) || isHan(value)).count();
        return informationCharacters < 2;
    }

    private static String prefix(String heading, RetrievalChunkType type, String tableHeader) {
        StringBuilder prefix = new StringBuilder();
        if (heading != null && !heading.isBlank()) {
            prefix.append("[章节] ").append(heading).append('\n');
        }
        prefix.append("[内容类型] ").append(type.name().toLowerCase(Locale.ROOT)).append('\n');
        if (tableHeader != null && !tableHeader.isBlank()) {
            prefix.append("[表头] ").append(tableHeader).append('\n');
        }
        return prefix.append("[正文] ").toString();
    }

    private static boolean sameContext(RetrievalChunkProjection first, RetrievalChunkProjection second) {
        return Objects.equals(first.sectionId(), second.sectionId()) && Objects.equals(first.pageId(), second.pageId());
    }

    private static String language(String text) {
        long han = text.codePoints().filter(RetrievalChunkBuilder::isHan).count();
        long latin = text.codePoints().filter(value -> Character.UnicodeScript.of(value)
                == Character.UnicodeScript.LATIN).count();
        return han > 0 && han >= latin ? "zh" : latin > 0 ? "en" : "und";
    }

    private static boolean isHan(int value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }

    private static String withoutProjectionLabels(String text) {
        return PROJECTION_LABEL.matcher(SYNTHETIC_TYPE_LINE.matcher(text).replaceAll("")).replaceAll("");
    }

    private static TextRange trimRange(String text, TextRange range) {
        int start = range.start();
        int end = range.end();
        while (start < end) {
            int value = text.codePointAt(start);
            if (!Character.isWhitespace(value)) {
                break;
            }
            start += Character.charCount(value);
        }
        while (end > start) {
            int value = text.codePointBefore(end);
            if (!Character.isWhitespace(value)) {
                break;
            }
            end -= Character.charCount(value);
        }
        if (start == end) {
            throw new IllegalArgumentException("retrieval range cannot contain only whitespace");
        }
        return new TextRange(start, end);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }

    private record TextRange(int start, int end) { }

    private record AnchorSelection(String text, List<String> evidenceIds) {
        private AnchorSelection {
            text = Objects.requireNonNull(text, "text");
            evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
            if (text.isBlank()) {
                throw new IllegalArgumentException("auxiliary chunk text is required");
            }
        }
    }
}
