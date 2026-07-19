package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;
import org.zipp.ai.domain.ingestion.service.EvidenceUnitBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceUnitBuilderTest {

    @Test
    void buildsCitableTextAndVisualEvidenceWithRegionsAndRelations() {
        List<CanonicalPage> pages = List.of(
                page(1, List.of(
                        block("header-1", "Agile Guide", TextBlockKind.PARAGRAPH,
                                BoilerplatePosition.HEADER_CANDIDATE, 1, 0.01),
                        block("heading", "Delivery", TextBlockKind.HEADING,
                                BoilerplatePosition.NONE, 2, 0.10),
                        block("paragraph", "Deliver in short iterations.", TextBlockKind.PARAGRAPH,
                                BoilerplatePosition.NONE, 3, 0.20),
                        block("list-1", "Plan", TextBlockKind.LIST_ITEM,
                                BoilerplatePosition.NONE, 4, 0.30),
                        block("list-2", "Build", TextBlockKind.LIST_ITEM,
                                BoilerplatePosition.NONE, 5, 0.36),
                        block("caption", "Figure 1. Sprint loop", TextBlockKind.CAPTION,
                                BoilerplatePosition.NONE, 6, 0.70)),
                        List.of(new NormalizedBoundingBox(0.1, 0.45, 0.9, 0.68))),
                page(2, List.of(block("header-2", "Agile Guide", TextBlockKind.PARAGRAPH,
                        BoilerplatePosition.HEADER_CANDIDATE, 1, 0.01)), List.of()),
                page(3, List.of(block("header-3", "Agile Guide", TextBlockKind.PARAGRAPH,
                        BoilerplatePosition.HEADER_CANDIDATE, 1, 0.01)), List.of()));
        DocumentStructure structure = new DocumentStructureBuilder().build(pages);
        VisualCandidate candidate = structure.visualCandidates().get(0);
        StoredArtifact crop = artifact("visual.png", "visual-version", "b");
        VisualCropManifest crops = new VisualCropManifest("visual-crop-manifest-v1",
                structure.structureHash(), "selection-v1", 1, 0,
                List.of(new VisualCropArtifact("page_1", candidate, crop)));
        List<EvidenceSourcePage> sources = List.of(
                source("page_1", pages.get(0), "a1"),
                source("page_2", pages.get(1), "a2"),
                source("page_3", pages.get(2), "a3"));

        EvidenceManifest manifest = new EvidenceUnitBuilder().build(
                "rev_1", "version_1", structure, sources, crops);

        assertEquals("evidence-manifest-v1", manifest.schemaVersion());
        assertFalse(manifest.units().stream().anyMatch(unit -> "Agile Guide".equals(unit.displayText())));
        assertEquals(1, manifest.units().stream().filter(unit -> unit.unitType() == EvidenceUnitType.LIST).count());
        EvidenceUnit visual = manifest.units().stream()
                .filter(unit -> unit.modality() == EvidenceModality.VISUAL).findFirst().orElseThrow();
        assertEquals(crop, visual.visualArtifact());
        assertTrue(manifest.relations().stream().anyMatch(relation ->
                relation.relationType() == EvidenceRelationType.CAPTION_OF
                        && relation.toEvidenceId().equals(visual.evidenceId())));
        assertTrue(manifest.relations().stream().anyMatch(relation ->
                relation.relationType() == EvidenceRelationType.NEXT_IN_SECTION));
        assertEquals(1, manifest.sectionHeadings().size());
        assertEquals("heading", manifest.sectionHeadings().get(0).sourceBlockRef());
        assertTrue(manifest.units().stream().allMatch(unit -> !unit.regions().isEmpty()));
    }

    @Test
    void assignsBlocksToTheLatestPrecedingHeadingWhenSectionsShareAPage() {
        CanonicalPage page = page(1, List.of(
                block("heading-a", "A", TextBlockKind.HEADING, BoilerplatePosition.NONE, 1, 0.10),
                block("paragraph-a", "First section", TextBlockKind.PARAGRAPH,
                        BoilerplatePosition.NONE, 2, 0.20),
                block("heading-b", "B", TextBlockKind.HEADING, BoilerplatePosition.NONE, 3, 0.40),
                block("paragraph-b", "Second section", TextBlockKind.PARAGRAPH,
                        BoilerplatePosition.NONE, 4, 0.50)), List.of());
        DocumentStructure structure = new DocumentStructureBuilder().build(List.of(page));
        EvidenceManifest manifest = new EvidenceUnitBuilder().build("rev_1", "version_1", structure,
                List.of(source("page_1", page, "a")), new VisualCropManifest(
                        "visual-crop-manifest-v1", structure.structureHash(), "selection-v1", 0, 0, List.of()));

        String firstSection = manifest.units().stream()
                .filter(unit -> "First section".equals(unit.displayText())).findFirst().orElseThrow().sectionId();
        String secondSection = manifest.units().stream()
                .filter(unit -> "Second section".equals(unit.displayText())).findFirst().orElseThrow().sectionId();

        assertEquals(structure.sections().get(0).sectionId(), firstSection);
        assertEquals(structure.sections().get(1).sectionId(), secondSection);
        var byId = manifest.units().stream().collect(java.util.stream.Collectors.toMap(
                EvidenceUnit::evidenceId, unit -> unit));
        assertTrue(manifest.relations().stream()
                .filter(relation -> relation.relationType() == EvidenceRelationType.NEXT_IN_SECTION)
                .allMatch(relation -> byId.get(relation.fromEvidenceId()).sectionId()
                        .equals(byId.get(relation.toEvidenceId()).sectionId())));
    }

    @Test
    void leavesTextBeforeTheFirstSamePageHeadingUnsectioned() {
        CanonicalPage page = page(1, List.of(
                block("preface", "Preface", TextBlockKind.PARAGRAPH,
                        BoilerplatePosition.NONE, 1, 0.05),
                block("heading-a", "A", TextBlockKind.HEADING,
                        BoilerplatePosition.NONE, 2, 0.10),
                block("paragraph-a", "First section", TextBlockKind.PARAGRAPH,
                        BoilerplatePosition.NONE, 3, 0.20)), List.of());
        DocumentStructure structure = new DocumentStructureBuilder().build(List.of(page));

        EvidenceManifest manifest = new EvidenceUnitBuilder().build("rev_1", "version_1", structure,
                List.of(source("page_1", page, "a")), new VisualCropManifest(
                        "visual-crop-manifest-v1", structure.structureHash(), "selection-v1", 0, 0, List.of()));

        assertNull(manifest.units().stream().filter(unit -> "Preface".equals(unit.displayText()))
                .findFirst().orElseThrow().sectionId());
    }

    @Test
    void splitsLongParagraphsAndBuildsTableHeaderRowGroups() {
        String longParagraph = "First sentence. " + "a".repeat(2_100) + ". Last sentence.";
        String table = "Name|Value\nA|1\nB|2\nC|3\nD|4\nE|5\nF|6\nG|7";
        CanonicalPage page = page(1, List.of(
                block("heading", "Data", TextBlockKind.HEADING, BoilerplatePosition.NONE, 1, 0.05),
                block("long", longParagraph, TextBlockKind.PARAGRAPH,
                        BoilerplatePosition.NONE, 2, 0.15),
                tableBlock("table", table, 1, 3, 0.50)), List.of());
        DocumentStructure structure = new DocumentStructureBuilder().build(List.of(page));

        EvidenceManifest manifest = new EvidenceUnitBuilder().build("rev_1", "version_1", structure,
                List.of(source("page_1", page, "a")), new VisualCropManifest(
                        "visual-crop-manifest-v1", structure.structureHash(), "selection-v1", 0, 0, List.of()));

        List<EvidenceUnit> content = manifest.units().stream()
                .filter(unit -> unit.unitType() == EvidenceUnitType.CONTENT).toList();
        assertTrue(content.size() > 1);
        assertTrue(content.stream().allMatch(unit -> unit.displayText().length() <= 2_000));
        assertEquals(1, manifest.units().stream()
                .filter(unit -> unit.unitType() == EvidenceUnitType.TABLE_HEADER).count());
        assertEquals(2, manifest.units().stream()
                .filter(unit -> unit.unitType() == EvidenceUnitType.TABLE_ROW_GROUP).count());
        assertEquals(2, manifest.relations().stream()
                .filter(relation -> relation.relationType() == EvidenceRelationType.TABLE_HEADER_FOR).count());
    }

    @Test
    void doesNotInventAHeaderForATableWithoutExplicitHeaderMetadata() {
        String table = "A|1\nB|2\nC|3";
        CanonicalPage page = page(1, List.of(tableBlock("table", table, 0, 1, 0.20)), List.of());
        DocumentStructure structure = new DocumentStructureBuilder().build(List.of(page));

        EvidenceManifest manifest = new EvidenceUnitBuilder().build("rev_1", "version_1", structure,
                List.of(source("page_1", page, "a")), new VisualCropManifest(
                        "visual-crop-manifest-v1", structure.structureHash(), "selection-v1", 0, 0, List.of()));

        assertEquals(0, manifest.units().stream()
                .filter(unit -> unit.unitType() == EvidenceUnitType.TABLE_HEADER).count());
        assertEquals(1, manifest.units().stream()
                .filter(unit -> unit.unitType() == EvidenceUnitType.TABLE_ROW_GROUP).count());
        assertEquals(0, manifest.relations().stream()
                .filter(relation -> relation.relationType() == EvidenceRelationType.TABLE_HEADER_FOR).count());
    }

    @Test
    void assignsTheSyntheticRootSectionWhenTheDocumentHasNoHeading() {
        CanonicalPage page = page(1, List.of(block("paragraph", "Body", TextBlockKind.PARAGRAPH,
                BoilerplatePosition.NONE, 1, 0.10)), List.of());
        DocumentStructure structure = new DocumentStructureBuilder().build(List.of(page));

        EvidenceManifest manifest = new EvidenceUnitBuilder().build("rev_1", "version_1", structure,
                List.of(source("page_1", page, "a")), new VisualCropManifest(
                        "visual-crop-manifest-v1", structure.structureHash(), "selection-v1", 0, 0, List.of()));

        assertEquals(structure.sections().get(0).sectionId(), manifest.units().get(0).sectionId());
    }

    @Test
    void keepsSurrogatePairsIntactWhenHardSplittingALongParagraph() {
        String paragraph = "a".repeat(1_999) + "🚀" + "b".repeat(10);
        CanonicalPage page = page(1, List.of(block("paragraph", paragraph, TextBlockKind.PARAGRAPH,
                BoilerplatePosition.NONE, 1, 0.10)), List.of());
        DocumentStructure structure = new DocumentStructureBuilder().build(List.of(page));

        EvidenceManifest manifest = new EvidenceUnitBuilder().build("rev_1", "version_1", structure,
                List.of(source("page_1", page, "a")), new VisualCropManifest(
                        "visual-crop-manifest-v1", structure.structureHash(), "selection-v1", 0, 0, List.of()));

        List<EvidenceUnit> units = manifest.units().stream()
                .filter(unit -> unit.unitType() == EvidenceUnitType.CONTENT).toList();
        assertEquals(paragraph, units.stream().map(EvidenceUnit::displayText)
                .collect(java.util.stream.Collectors.joining()));
        assertTrue(units.stream().noneMatch(unit ->
                Character.isLowSurrogate(unit.displayText().charAt(0))
                        || Character.isHighSurrogate(unit.displayText().charAt(unit.displayText().length() - 1))));
    }

    private static EvidenceSourcePage source(String pageId, CanonicalPage page, String hashSeed) {
        return new EvidenceSourcePage(pageId, page, artifact("canonical-" + page.pageNo(),
                "canonical-version-" + page.pageNo(), hashSeed));
    }

    private static StoredArtifact artifact(String key, String version, String hashSeed) {
        return new StoredArtifact(key, version, hashSeed.repeat(64).substring(0, 64), 100,
                key.endsWith(".png") ? "image/png" : "application/json+gzip");
    }

    private static CanonicalPage page(int pageNo, List<CanonicalBlock> blocks,
                                      List<NormalizedBoundingBox> rasterRegions) {
        return new CanonicalPage(pageNo, 1000, 1400, blocks,
                NativeTextQuality.empty(), null, false, rasterRegions);
    }

    private static CanonicalBlock block(String id, String text, TextBlockKind kind,
                                        BoilerplatePosition boilerplate, int order, double top) {
        NormalizedBoundingBox region = new NormalizedBoundingBox(0.1, top, 0.9, top + 0.04);
        return new CanonicalBlock(id, kind, order, List.of(region), TextSource.NATIVE, text, text,
                List.of(new SourceMapSpan(0, text.length(), 0, text.length(), List.of(region))),
                0.95, boilerplate);
    }

    private static CanonicalBlock tableBlock(String id, String text, int headerRows,
                                             int order, double top) {
        NormalizedBoundingBox region = new NormalizedBoundingBox(0.1, top, 0.9, top + 0.04);
        return new CanonicalBlock(id, TextBlockKind.TABLE, order, List.of(region), TextSource.NATIVE,
                text, text, List.of(new SourceMapSpan(0, text.length(), 0, text.length(), List.of(region))),
                0.95, BoilerplatePosition.NONE, headerRows);
    }
}
