package org.zipp.ai.domain.ingestion;

import org.junit.jupiter.api.Test;
import org.zipp.ai.domain.ingestion.model.valobj.TextBlockKind;
import org.zipp.ai.domain.ingestion.service.TextBlockKindPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextBlockKindPolicyTest {

    private final TextBlockKindPolicy policy = new TextBlockKindPolicy();

    @Test
    void strongHeadingGeometryWinsForNumberedSectionTitles() {
        assertEquals(TextBlockKind.HEADING,
                policy.classify(TextBlockKind.PARAGRAPH, "1. Introduction", 0.025));
        assertEquals(TextBlockKind.LIST_ITEM,
                policy.classify(TextBlockKind.PARAGRAPH, "1. First step", 0.012));
    }

    @Test
    void requiresConsistentMultipleRowsBeforeClassifyingDelimitedTextAsATable() {
        assertEquals(TextBlockKind.PARAGRAPH,
                policy.classify(TextBlockKind.PARAGRAPH, "Use A | B for a union", 0.012));
        assertEquals(TextBlockKind.TABLE,
                policy.classify(TextBlockKind.PARAGRAPH, "Name|Value\nA|1\nB|2", 0.012));
    }
}
