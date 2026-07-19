package org.zipp.ai.domain.ingestion.service;

import org.zipp.ai.domain.ingestion.model.valobj.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;

/** Deterministic document-level structure policy over immutable canonical pages. */
public final class DocumentStructureBuilder {

    private static final String SCHEMA_VERSION = "document-structure-v1";

    public String fingerprint() {
        return SCHEMA_VERSION + ":boilerplate-60pct-min3:visual-caption-gap=.15";
    }

    public DocumentStructure build(List<CanonicalPage> canonicalPages) {
        List<CanonicalPage> pages = canonicalPages.stream()
                .sorted(Comparator.comparingInt(CanonicalPage::pageNo)).toList();
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("document structure requires canonical pages");
        }
        List<BoilerplateBlockRef> boilerplate = confirmBoilerplate(pages);
        List<DocumentSection> sections = sections(pages);
        List<VisualCandidate> visuals = visualCandidates(pages);
        String hashInput = fingerprint() + ":" + sections + ":" + boilerplate + ":" + visuals;
        return new DocumentStructure(SCHEMA_VERSION, sections, boilerplate, visuals, sha256(hashInput));
    }

    private static List<BoilerplateBlockRef> confirmBoilerplate(List<CanonicalPage> pages) {
        Map<String, Set<Integer>> pagesByCandidate = new HashMap<>();
        Map<String, List<BoilerplateBlockRef>> occurrences = new HashMap<>();
        for (CanonicalPage page : pages) {
            for (CanonicalBlock block : page.blocks()) {
                if (block.boilerplatePosition() == BoilerplatePosition.NONE) {
                    continue;
                }
                String normalized = normalizeBoilerplate(block.displayText());
                if (normalized.isBlank()) {
                    continue;
                }
                String key = block.boilerplatePosition() + ":" + normalized;
                pagesByCandidate.computeIfAbsent(key, ignored -> new HashSet<>()).add(page.pageNo());
                occurrences.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new BoilerplateBlockRef(
                        page.pageNo(), block.blockId(), normalized, block.boilerplatePosition()));
            }
        }
        int minimumPages = Math.max(3, (int) Math.ceil(pages.size() * 0.60));
        return occurrences.entrySet().stream()
                .filter(entry -> pagesByCandidate.get(entry.getKey()).size() >= minimumPages)
                .flatMap(entry -> entry.getValue().stream())
                .sorted(Comparator.comparingInt(BoilerplateBlockRef::pageNo)
                        .thenComparing(BoilerplateBlockRef::blockId)).toList();
    }

    private static List<DocumentSection> sections(List<CanonicalPage> pages) {
        record Heading(int pageNo, String blockId, String text) { }
        List<Heading> headings = pages.stream().flatMap(page -> page.blocks().stream()
                .filter(block -> block.kind() == TextBlockKind.HEADING)
                .map(block -> new Heading(page.pageNo(), block.blockId(), block.displayText()))).toList();
        int lastPage = pages.get(pages.size() - 1).pageNo();
        if (headings.isEmpty()) {
            String hash = sha256("root:" + pages.get(0).pageNo() + ":" + lastPage);
            return List.of(new DocumentSection("sec_" + hash.substring(0, 24), null, 1, 1,
                    pages.get(0).pageNo(), lastPage, null, hash));
        }
        List<DocumentSection> sections = new ArrayList<>();
        for (int index = 0; index < headings.size(); index++) {
            Heading heading = headings.get(index);
            int pageEnd = index + 1 == headings.size() ? lastPage
                    : Math.max(heading.pageNo(), headings.get(index + 1).pageNo() - 1);
            String hash = sha256(heading.blockId() + ":" + heading.text() + ":" + heading.pageNo() + ":" + pageEnd);
            sections.add(new DocumentSection("sec_" + hash.substring(0, 24), null, 1, index + 1,
                    heading.pageNo(), pageEnd, heading.blockId(), hash));
        }
        return List.copyOf(sections);
    }

    private static List<VisualCandidate> visualCandidates(List<CanonicalPage> pages) {
        List<VisualCandidate> candidates = new ArrayList<>();
        for (CanonicalPage page : pages) {
            for (int index = 0; index < page.rasterRegions().size(); index++) {
                NormalizedBoundingBox region = page.rasterRegions().get(index);
                String caption = nearestCaption(page.blocks(), region);
                String identity = page.pageNo() + ":" + index + ":" + region + ":" + caption;
                candidates.add(new VisualCandidate("vis_" + sha256(identity).substring(0, 24), page.pageNo(),
                        List.of(region), caption));
            }
        }
        return List.copyOf(candidates);
    }

    private static String nearestCaption(List<CanonicalBlock> blocks, NormalizedBoundingBox visual) {
        return blocks.stream().filter(block -> block.kind() == TextBlockKind.CAPTION)
                .filter(block -> horizontalOverlap(block, visual) >= 0.20)
                .map(block -> Map.entry(block.blockId(), verticalGap(block, visual)))
                .filter(entry -> entry.getValue() <= 0.15)
                .min(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static double horizontalOverlap(CanonicalBlock block, NormalizedBoundingBox visual) {
        double left = block.regions().stream().mapToDouble(NormalizedBoundingBox::x1).min().orElse(1);
        double right = block.regions().stream().mapToDouble(NormalizedBoundingBox::x2).max().orElse(0);
        double overlap = Math.max(0, Math.min(right, visual.x2()) - Math.max(left, visual.x1()));
        return overlap / Math.min(right - left, visual.x2() - visual.x1());
    }

    private static double verticalGap(CanonicalBlock block, NormalizedBoundingBox visual) {
        double top = block.regions().stream().mapToDouble(NormalizedBoundingBox::y1).min().orElse(1);
        double bottom = block.regions().stream().mapToDouble(NormalizedBoundingBox::y2).max().orElse(0);
        if (bottom < visual.y1()) {
            return visual.y1() - bottom;
        }
        return top > visual.y2() ? top - visual.y2() : 0;
    }

    private static String normalizeBoilerplate(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).trim()
                .replaceAll("\\b\\d+\\b", "{page}").replaceAll("\\s+", " ");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
