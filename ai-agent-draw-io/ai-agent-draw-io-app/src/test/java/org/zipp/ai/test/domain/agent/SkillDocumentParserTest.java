package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillDocumentParser;

import static org.junit.Assert.assertEquals;

public class SkillDocumentParserTest {

    @Test
    public void shouldParseExplicitSectionPriorities() {
        SkillDocumentParser.SkillDocument document = new SkillDocumentParser().parse("""
                # Demo Skill

                ## Rules [P0]
                Keep this.

                ## Anti-Patterns [P1]
                Drop this first.
                """);

        assertEquals("# Demo Skill", document.intro());
        assertEquals(2, document.sections().size());
        assertEquals("rules", document.sections().get(0).id());
        assertEquals("Rules", document.sections().get(0).title());
        assertEquals("P0", document.sections().get(0).priority());
        assertEquals("anti-patterns", document.sections().get(1).id());
        assertEquals("P1", document.sections().get(1).priority());
    }

    @Test
    public void shouldInferPrioritiesForCurrentDrawioSkillHeadings() {
        SkillDocumentParser.SkillDocument document = new SkillDocumentParser().parse("""
                # Architecture Skill

                ## View Selection
                Pick the view.

                ## Golden Example (runtime view - JVM)
                Example.

                ## Checklist
                Self-check.
                """);

        assertEquals("P0", document.sections().get(0).priority());
        assertEquals("P0", document.sections().get(1).priority());
        assertEquals("P1", document.sections().get(2).priority());
    }
}
