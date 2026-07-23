package org.zipp.ai.domain.retrieval;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.retrieval.model.valobj.ChunkEvidenceRole;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalChunkType;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalIndexMode;
import org.zipp.ai.domain.retrieval.projection.RetrievalChunkBuilder;
import org.zipp.ai.domain.retrieval.projection.RetrievalTokenCounter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalChunkBuilderTest {

    private static final RetrievalTokenCounter CHARACTER_COUNTER = new RetrievalTokenCounter() {
        @Override
        public int count(String text) {
            return text.codePointCount(0, text.length());
        }

        @Override
        public String fingerprint() {
            return "test-codepoint-v1";
        }
    };

    @Test
    void buildsCitableLeavesParentContextsAuxiliaryChunksAndLexicalSignals() {
        List<EvidenceUnit> units = new ArrayList<>();
        units.add(text("heading", EvidenceUnitType.HEADING, "Agile Delivery", "section-1", 1, 0.95));
        for (int index = 1; index <= 16; index++) {
            units.add(text("content-" + index, EvidenceUnitType.CONTENT,
                    "Iteration " + index + " ships API42 in 24ms.", "section-1", index + 1, 0.90));
        }
        units.add(text("table-header", EvidenceUnitType.TABLE_HEADER, "Name|Value", "section-1", 10, 0.90));
        units.add(text("table-row", EvidenceUnitType.TABLE_ROW_GROUP, "Sprint|14", "section-1", 11, 0.90));
        units.add(text("caption", EvidenceUnitType.CAPTION, "Figure 1. Sprint loop", "section-1", 12, 0.90));
        units.add(visual("visual", "section-1", 13));
        EvidenceManifest evidence = manifest(units, List.of(
                new EvidenceRelation("table-header", "table-row", EvidenceRelationType.TABLE_HEADER_FOR, 1),
                new EvidenceRelation("caption", "visual", EvidenceRelationType.CAPTION_OF, 1)));
        RetrievalChunkBuilder builder = new RetrievalChunkBuilder(CHARACTER_COUNTER);

        var projection = builder.build(evidence);

        assertEquals(projection.projectionHash(), builder.build(evidence).projectionHash());
        assertTrue(projection.chunks().stream().allMatch(chunk -> chunk.tokenCount() <= switch (chunk.chunkType()) {
            case CAPTION_CONTEXT -> 320;
            case LIST_GROUP, TABLE_ROW_GROUP -> 400;
            case CONTENT, VISUAL_DESCRIPTION -> 420;
            case PAGE_PARENT -> 900;
            case SECTION_BRIDGE, DOCUMENT_PROFILE -> 380;
        }));
        assertTrue(projection.chunks().stream().filter(chunk -> chunk.citable())
                .allMatch(chunk -> chunk.parentContext() != null && !chunk.parentEvidenceIds().isEmpty()
                        && chunk.evidenceMappings().stream()
                        .anyMatch(mapping -> mapping.role() == ChunkEvidenceRole.PRIMARY)));
        assertTrue(projection.chunks().stream().filter(chunk -> chunk.citable()
                        && chunk.chunkType() != RetrievalChunkType.PAGE_PARENT)
                .allMatch(chunk -> chunk.evidenceMappings().stream()
                        .anyMatch(mapping -> mapping.evidenceId().equals("heading")
                                && mapping.role() == ChunkEvidenceRole.HEADER)));
        assertTrue(projection.chunks().stream().anyMatch(chunk ->
                chunk.chunkType() == RetrievalChunkType.TABLE_ROW_GROUP
                        && chunk.retrievalText().contains("[表头] Name|Value")
                        && chunk.evidenceMappings().stream()
                        .anyMatch(mapping -> mapping.role() == ChunkEvidenceRole.HEADER)));
        assertTrue(projection.chunks().stream().anyMatch(chunk ->
                chunk.chunkType() == RetrievalChunkType.VISUAL_DESCRIPTION
                        && chunk.evidenceMappings().stream()
                        .anyMatch(mapping -> mapping.role() == ChunkEvidenceRole.CAPTION)));
        assertTrue(projection.chunks().stream().anyMatch(chunk ->
                chunk.chunkType() == RetrievalChunkType.SECTION_BRIDGE && !chunk.citable()));
        assertTrue(projection.chunks().stream().anyMatch(chunk ->
                chunk.chunkType() == RetrievalChunkType.DOCUMENT_PROFILE && !chunk.citable()));
        assertTrue(projection.lexicalProjections().stream().flatMap(value -> value.exactTerms().stream())
                .anyMatch(term -> term.normalizedTerm().equals("api42")));
        assertTrue(projection.lexicalProjections().stream().flatMap(value -> value.exactTerms().stream())
                .noneMatch(term -> term.normalizedTerm().equals("content")));
        projection.chunks().stream().filter(chunk -> !chunk.citable()).forEach(chunk -> {
            List<Integer> ordinals = chunk.evidenceMappings().stream().map(mapping -> mapping.ordinal()).toList();
            assertEquals(java.util.stream.IntStream.range(0, ordinals.size()).boxed().toList(), ordinals);
        });
    }

    @Test
    void splitsOversizedEvidenceAtCodePointBoundariesAndKeepsExactCharacterMappings() {
        String body = "a".repeat(390) + "🚀" + "b".repeat(390);
        EvidenceManifest evidence = manifest(List.of(
                text("content", EvidenceUnitType.CONTENT, body, null, 1, 0.90)), List.of());

        var chunks = new RetrievalChunkBuilder(CHARACTER_COUNTER).build(evidence).chunks().stream()
                .filter(chunk -> chunk.chunkType() == RetrievalChunkType.CONTENT).toList();

        assertTrue(chunks.size() > 1);
        String rebuilt = chunks.stream().map(chunk -> {
            var mapping = chunk.evidenceMappings().get(0);
            assertNotNull(mapping.charStart());
            assertNotNull(mapping.charEnd());
            return body.substring(mapping.charStart(), mapping.charEnd()).strip();
        }).reduce("", String::concat);
        assertEquals(body, rebuilt);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.tokenCount() <= 420));
    }

    @Test
    void keepsUncaptionedVisualEvidenceOutOfLexicalAndDenseProjection() {
        EvidenceManifest evidence = manifest(List.of(visual("visual", null, 1)), List.of());

        var projection = new RetrievalChunkBuilder(CHARACTER_COUNTER).build(evidence);
        var visual = projection.chunks().stream()
                .filter(chunk -> chunk.chunkType() == RetrievalChunkType.VISUAL_DESCRIPTION).findFirst().orElseThrow();

        assertEquals(RetrievalIndexMode.UNSEARCHABLE, visual.indexMode());
        assertTrue(projection.lexicalProjections().stream()
                .noneMatch(value -> value.chunkId().equals(visual.chunkId())));
    }

    @Test
    void doesNotOpenTheWordLaneOnlyBecauseTheChunkTypeLabelIsEnglish() {
        EvidenceManifest evidence = manifest(List.of(
                text("content", EvidenceUnitType.CONTENT, "敏捷开发流程", null, 1, 0.90)), List.of());

        var projection = new RetrievalChunkBuilder(CHARACTER_COUNTER).build(evidence);
        var content = projection.chunks().stream()
                .filter(chunk -> chunk.chunkType() == RetrievalChunkType.CONTENT).findFirst().orElseThrow();
        var lexical = projection.lexicalProjections().stream()
                .filter(value -> value.chunkId().equals(content.chunkId())).findFirst().orElseThrow();

        assertNull(lexical.wordSearchText());
        assertNotNull(lexical.cjkSearchText());
        assertEquals("zh", content.languagePrimary());
    }

    @Test
    void keepsEnglishSourceOutOfTheCjkLaneAndExtractsOnlyStrongExactTerms() {
        String body = "[RFC 2119] Agile ships API42 during \"Sprint Review\" on 2026-07-19 for $25 using v2.1.";
        EvidenceManifest evidence = manifest(List.of(
                text("content", EvidenceUnitType.CONTENT, body, null, 1, 0.90)), List.of());

        var projection = new RetrievalChunkBuilder(CHARACTER_COUNTER).build(evidence);
        var content = projection.chunks().stream()
                .filter(chunk -> chunk.chunkType() == RetrievalChunkType.CONTENT).findFirst().orElseThrow();
        var lexical = projection.lexicalProjections().stream()
                .filter(value -> value.chunkId().equals(content.chunkId())).findFirst().orElseThrow();
        List<String> exactTerms = lexical.exactTerms().stream().map(term -> term.normalizedTerm()).toList();

        assertEquals("en", content.languagePrimary());
        assertNotNull(lexical.wordSearchText());
        assertNull(lexical.cjkSearchText());
        assertTrue(exactTerms.containsAll(List.of("rfc", "api42", "sprint review",
                "2026-07-19", "$25", "v2.1")));
        assertFalse(exactTerms.contains("agile"));
        assertFalse(exactTerms.contains("ships"));
    }

    @Test
    void enforcesTheAuxiliaryRatioAndKeepsLowInformationEvidenceUnsearchable() {
        List<EvidenceUnit> units = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            units.add(text("content-" + index, EvidenceUnitType.CONTENT,
                    "x".repeat(100) + index, "section-1", index, 0.90));
        }
        units.add(text("page-number", EvidenceUnitType.CONTENT, "Page 12", null, 10, 0.90));
        units.add(text("isolated", EvidenceUnitType.CONTENT, "✓", null, 11, 0.90));
        units.add(text("year", EvidenceUnitType.CONTENT, "2024", null, 12, 0.90));
        units.add(bottomText("bare-footer", EvidenceUnitType.FOOTNOTE, "12", null, 13, 0.90));

        var projection = new RetrievalChunkBuilder(CHARACTER_COUNTER).build(manifest(units, List.of()));
        long searchableLeaves = projection.chunks().stream()
                .filter(chunk -> chunk.citable() && chunk.indexMode() != RetrievalIndexMode.UNSEARCHABLE).count();
        long auxiliary = projection.chunks().stream().filter(chunk -> !chunk.citable()).count();

        assertTrue(auxiliary <= Math.floor(searchableLeaves * 0.20));
        assertEquals(0, auxiliary);
        assertTrue(projection.chunks().stream()
                .filter(chunk -> chunk.evidenceMappings().stream().anyMatch(mapping ->
                        mapping.evidenceId().equals("page-number") || mapping.evidenceId().equals("isolated")
                                || mapping.evidenceId().equals("bare-footer")))
                .allMatch(chunk -> chunk.indexMode() == RetrievalIndexMode.UNSEARCHABLE));
        assertTrue(projection.chunks().stream()
                .filter(chunk -> chunk.evidenceMappings().stream()
                        .anyMatch(mapping -> mapping.evidenceId().equals("year")))
                .allMatch(chunk -> chunk.indexMode() == RetrievalIndexMode.DENSE_AND_LEXICAL));
    }

    @Test
    void mergesAShortTrailingBlockBackwardWhenTheCombinedChunkFits() {
        EvidenceManifest evidence = manifest(List.of(
                text("content-main", EvidenceUnitType.CONTENT, "a".repeat(100), "section-1", 1, 0.90),
                text("content-tail", EvidenceUnitType.CONTENT, "tail", "section-1", 2, 0.90)), List.of());

        var content = new RetrievalChunkBuilder(CHARACTER_COUNTER).build(evidence).chunks().stream()
                .filter(chunk -> chunk.chunkType() == RetrievalChunkType.CONTENT).toList();

        assertEquals(1, content.size());
        assertEquals(List.of("content-main", "content-tail"), content.get(0).evidenceMappings().stream()
                .filter(mapping -> mapping.role() == ChunkEvidenceRole.PRIMARY)
                .map(mapping -> mapping.evidenceId()).toList());
    }

    @Test
    void projectsOneSearchableCitableParentForTheWholeSourcePage() {
        EvidenceManifest evidence = manifest(List.of(
                text("page-fact", EvidenceUnitType.CONTENT,
                        "Existing evidence remains pinned to V1.", "section-1", 1, 0.90),
                text("page-policy", EvidenceUnitType.CONTENT,
                        "New requests use the latest ready material version.", "section-2", 2, 0.90)), List.of());

        var parent = new RetrievalChunkBuilder(CHARACTER_COUNTER).build(evidence).chunks().stream()
                .filter(chunk -> chunk.chunkType() == RetrievalChunkType.PAGE_PARENT).findFirst().orElseThrow();

        assertEquals("page-1", parent.pageId());
        assertTrue(parent.citable());
        assertEquals(RetrievalIndexMode.DENSE_AND_LEXICAL, parent.indexMode());
        assertTrue(parent.retrievalText().contains("Existing evidence remains pinned to V1."));
        assertTrue(parent.retrievalText().contains("New requests use the latest ready material version."));
        assertEquals(List.of("page-fact", "page-policy"), parent.evidenceMappings().stream()
                .filter(mapping -> mapping.role() == ChunkEvidenceRole.PRIMARY)
                .map(mapping -> mapping.evidenceId()).toList());
    }

    @Test
    void keepsPageParentsSourcePageLocalAndSplitsOversizedPagesWithoutLosingEvidence() {
        String first = "a".repeat(400);
        String second = "b".repeat(400);
        String third = "c".repeat(400);
        EvidenceManifest evidence = manifest(List.of(
                textOnPage("earlier-heading", EvidenceUnitType.HEADING, "Earlier page heading", "section-1",
                        "page-1", 1, 1, 0.95),
                textOnPage("first", EvidenceUnitType.CONTENT, first, "section-1", "page-2", 2, 2, 0.90),
                textOnPage("second", EvidenceUnitType.CONTENT, second, "section-1", "page-2", 2, 3, 0.90),
                textOnPage("third", EvidenceUnitType.CONTENT, third, "section-1", "page-2", 2, 4, 0.90),
                textOnPage("other-page", EvidenceUnitType.CONTENT, "Independent page evidence", "section-2",
                        "page-3", 3, 5, 0.90)), List.of());

        var parents = new RetrievalChunkBuilder(CHARACTER_COUNTER).build(evidence).chunks().stream()
                .filter(chunk -> chunk.chunkType() == RetrievalChunkType.PAGE_PARENT).toList();
        var pageTwoParents = parents.stream().filter(chunk -> chunk.pageId().equals("page-2")).toList();

        assertTrue(pageTwoParents.size() > 1);
        assertTrue(pageTwoParents.stream().allMatch(chunk -> chunk.tokenCount() <= 900));
        assertTrue(pageTwoParents.stream().noneMatch(chunk -> chunk.retrievalText().contains("Earlier page heading")));
        assertEquals(List.of("first", "second", "third"), pageTwoParents.stream()
                .flatMap(chunk -> chunk.evidenceMappings().stream())
                .filter(mapping -> mapping.role() == ChunkEvidenceRole.PRIMARY)
                .map(mapping -> mapping.evidenceId()).toList());
        assertTrue(parents.stream().filter(chunk -> chunk.pageId().equals("page-3"))
                .allMatch(chunk -> chunk.evidenceMappings().stream()
                        .allMatch(mapping -> mapping.evidenceId().equals("other-page"))));
    }

    @Test
    void splitMappingsExactlyMatchTheTrimmedRetrievalFragmentsWithoutQuadraticSuffixCopies() {
        String body = "a".repeat(390) + "   " + "b".repeat(20_000);
        EvidenceManifest evidence = manifest(List.of(
                text("content", EvidenceUnitType.CONTENT, body, null, 1, 0.90)), List.of());

        var chunks = assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                new RetrievalChunkBuilder(CHARACTER_COUNTER).build(evidence).chunks().stream()
                        .filter(chunk -> chunk.chunkType() == RetrievalChunkType.CONTENT).toList());

        assertTrue(chunks.size() > 2);
        chunks.forEach(chunk -> {
            var mapping = chunk.evidenceMappings().get(0);
            String mapped = body.substring(mapping.charStart(), mapping.charEnd());
            assertEquals(mapped, chunk.retrievalText().substring(chunk.retrievalText().lastIndexOf("[正文] ") + 5));
            assertEquals(mapped, mapped.strip());
        });
    }

    @Test
    void auxiliaryTextRetainsEveryHeadingHeaderAndCaptionEvidenceIdentityItCopies() {
        List<EvidenceUnit> units = new ArrayList<>();
        units.add(text("heading", EvidenceUnitType.HEADING, "Architecture", "section-1", 1, 0.95));
        units.add(text("table-header", EvidenceUnitType.TABLE_HEADER, "Name|Value", "section-1", 2, 0.90));
        units.add(text("table-row", EvidenceUnitType.TABLE_ROW_GROUP, "Sprint|14", "section-1", 3, 0.90));
        units.add(text("caption", EvidenceUnitType.CAPTION, "Sprint loop", "section-1", 4, 0.90));
        units.add(visual("visual", "section-1", 5));
        for (int index = 1; index <= 5; index++) {
            units.add(text("content-" + index, EvidenceUnitType.CONTENT,
                    "x".repeat(100) + index, "section-1", 5 + index, 0.90));
        }
        EvidenceManifest evidence = manifest(units, List.of(
                new EvidenceRelation("table-header", "table-row", EvidenceRelationType.TABLE_HEADER_FOR, 1),
                new EvidenceRelation("caption", "visual", EvidenceRelationType.CAPTION_OF, 1)));

        var auxiliary = new RetrievalChunkBuilder(CHARACTER_COUNTER).build(evidence).chunks().stream()
                .filter(chunk -> !chunk.citable()).findFirst().orElseThrow();
        List<String> mapped = auxiliary.evidenceMappings().stream()
                .map(mapping -> mapping.evidenceId()).toList();

        assertTrue(mapped.containsAll(List.of("heading", "table-header", "table-row", "caption")));
        assertTrue(auxiliary.evidenceMappings().stream()
                .allMatch(mapping -> mapping.role() == ChunkEvidenceRole.REPRESENTATIVE));
    }

    @Test
    void partialAuxiliaryMapsTheExactPrimaryEvenWhenHeadingAndBodyShareAPrefix() {
        List<EvidenceUnit> units = new ArrayList<>();
        units.add(text("heading", EvidenceUnitType.HEADING,
                "Architecture " + "h".repeat(120), "section-1", 1, 0.95));
        units.add(text("primary", EvidenceUnitType.CONTENT,
                "Architecture " + "p".repeat(390), "section-1", 2, 0.90));
        for (int index = 1; index <= 4; index++) {
            units.add(text("other-" + index, EvidenceUnitType.CONTENT,
                    "z".repeat(100) + index, "section-1", index + 2, 0.90));
        }

        var auxiliary = new RetrievalChunkBuilder(CHARACTER_COUNTER)
                .build(manifest(units, List.of())).chunks().stream()
                .filter(chunk -> !chunk.citable()).findFirst().orElseThrow();
        List<String> mapped = auxiliary.evidenceMappings().stream()
                .map(mapping -> mapping.evidenceId()).toList();

        assertTrue(mapped.containsAll(List.of("heading", "primary")));
    }

    private static EvidenceManifest manifest(List<EvidenceUnit> units, List<EvidenceRelation> relations) {
        List<SectionHeadingEvidence> headings = units.stream()
                .filter(unit -> unit.unitType() == EvidenceUnitType.HEADING && unit.sectionId() != null)
                .map(unit -> new SectionHeadingEvidence(unit.sectionId(), unit.evidenceId(), unit.evidenceId()))
                .toList();
        return new EvidenceManifest("evidence-manifest-v1", "revision-1", "version-1",
                "a".repeat(64), "evidence-builder-v1", "b".repeat(64), units, relations, headings);
    }

    private static EvidenceUnit text(String id, EvidenceUnitType type, String text,
                                     String sectionId, int ordinal, double quality) {
        return textOnPage(id, type, text, sectionId, "page-1", 1, ordinal, quality);
    }

    private static EvidenceUnit textOnPage(String id, EvidenceUnitType type, String text, String sectionId,
                                           String pageId, int pageNo, int ordinal, double quality) {
        NormalizedBoundingBox box = new NormalizedBoundingBox(0.1, ordinal * 0.01,
                0.9, Math.min(0.99, ordinal * 0.01 + 0.005));
        return new EvidenceUnit(id, pageId, pageNo, sectionId, type, EvidenceModality.TEXT,
                "NATIVE", text, sha(text), artifact("canonical.json.gz"), null,
                List.of(new EvidenceRegion(pageId, pageNo, box, 0, text.length(), id)), quality);
    }

    private static EvidenceUnit bottomText(String id, EvidenceUnitType type, String text,
                                           String sectionId, int ordinal, double quality) {
        NormalizedBoundingBox box = new NormalizedBoundingBox(0.1, 0.92, 0.9, 0.95);
        return new EvidenceUnit(id, "page-1", 1, sectionId, type, EvidenceModality.TEXT,
                "NATIVE", text, sha(text), artifact("canonical.json.gz"), null,
                List.of(new EvidenceRegion("page-1", 1, box, 0, text.length(), id)), quality);
    }

    private static EvidenceUnit visual(String id, String sectionId, int ordinal) {
        NormalizedBoundingBox box = new NormalizedBoundingBox(0.1, ordinal * 0.01,
                0.9, Math.min(0.99, ordinal * 0.01 + 0.005));
        return new EvidenceUnit(id, "page-1", 1, sectionId, EvidenceUnitType.VISUAL,
                EvidenceModality.VISUAL, "VISUAL", null, null, null, artifact("visual.png"),
                List.of(new EvidenceRegion("page-1", 1, box, null, null, id)), 0.90);
    }

    private static StoredArtifact artifact(String key) {
        return new StoredArtifact(key, "object-version", "c".repeat(64), 100,
                key.endsWith(".png") ? "image/png" : "application/json+gzip");
    }

    private static String sha(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
