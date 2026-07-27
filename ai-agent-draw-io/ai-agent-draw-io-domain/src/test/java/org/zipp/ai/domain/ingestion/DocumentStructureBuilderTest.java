package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.*;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentStructureBuilderTest {

    @Test
    void confirmsRepeatedHeaderAtSixtyPercentAndKeepsRareChapterHeading() {
        List<CanonicalPage> pages = List.of(
                page(1, List.of(block("h1", "Agile Guide", TextBlockKind.PARAGRAPH,
                                BoilerplatePosition.HEADER_CANDIDATE, 0.01),
                        block("chapter-1", "Introduction", TextBlockKind.HEADING,
                                BoilerplatePosition.NONE, 0.20))),
                page(2, List.of(block("h2", "Agile Guide", TextBlockKind.PARAGRAPH,
                        BoilerplatePosition.HEADER_CANDIDATE, 0.01))),
                page(3, List.of(block("h3", "Agile Guide", TextBlockKind.PARAGRAPH,
                                BoilerplatePosition.HEADER_CANDIDATE, 0.01),
                        block("chapter-2", "Delivery", TextBlockKind.HEADING,
                                BoilerplatePosition.NONE, 0.20))),
                page(4, List.of()), page(5, List.of()));

        DocumentStructure structure = new DocumentStructureBuilder().build(pages);

        assertEquals(3, structure.boilerplateBlocks().size());
        assertEquals(2, structure.sections().size());
        assertEquals("chapter-1", structure.sections().get(0).headingBlockId());
        assertTrue(structure.boilerplateBlocks().stream()
                .noneMatch(ref -> ref.blockId().startsWith("chapter")));
    }

    @Test
    void createsVisualCandidateAndAssociatesTheNearestCaption() {
        CanonicalPage page = new CanonicalPage(1, 1000, 1400,
                List.of(block("caption", "Figure 1. Sprint loop", TextBlockKind.CAPTION,
                        BoilerplatePosition.NONE, 0.62)), NativeTextQuality.empty(), null, false,
                List.of(new NormalizedBoundingBox(0.1, 0.2, 0.9, 0.6)));

        DocumentStructure structure = new DocumentStructureBuilder().build(List.of(page));

        assertEquals(1, structure.visualCandidates().size());
        assertEquals("caption", structure.visualCandidates().get(0).captionBlockId());
        assertEquals(1, structure.sections().size());
    }

    private static CanonicalPage page(int pageNo, List<CanonicalBlock> blocks) {
        return new CanonicalPage(pageNo, 1000, 1400, blocks, NativeTextQuality.empty(), null, false, List.of());
    }

    private static CanonicalBlock block(String id, String text, TextBlockKind kind,
                                        BoilerplatePosition boilerplate, double top) {
        NormalizedBoundingBox region = new NormalizedBoundingBox(0.1, top, 0.9, top + 0.05);
        return new CanonicalBlock(id, kind, 1, List.of(region), TextSource.NATIVE, text, text,
                List.of(new SourceMapSpan(0, text.length(), 0, text.length(), List.of(region))),
                0.95, boilerplate);
    }
}
