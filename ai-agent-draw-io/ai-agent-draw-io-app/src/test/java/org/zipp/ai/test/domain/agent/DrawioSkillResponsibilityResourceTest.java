package org.zipp.ai.test.domain.agent;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks the slim skill architecture: two always-injected shared skills own structure and style,
 * each domain skill is compact (short rules + a golden example), and nobody re-bloats.
 */
public class DrawioSkillResponsibilityResourceTest {

    private static final List<String> DOMAIN_SKILLS = List.of(
            "drawio-architecture",
            "drawio-flowchart",
            "drawio-sequence",
            "drawio-er",
            "drawio-uml",
            "drawio-usecase",
            "drawio-state",
            "drawio-concept"
    );

    /**
     * Guard against prompt re-bloat: rewritten skills stay well below the old 13-17K prose files.
     * Golden example XML is the one thing worth spending budget on (architecture carries a full
     * runtime-view example), rule prose is not.
     */
    private static final int MAX_DOMAIN_SKILL_CHARS = 12000;
    private static final int MAX_SHARED_SKILL_CHARS = 10000;

    @Test
    public void sharedSkillsOwnStructureAndStyle() throws Exception {
        String xmlGuide = readSkill("drawio-xml-guide");
        String visualDesign = readSkill("drawio-visual-design");

        assertTrue(xmlGuide.contains("selectable: false"));
        assertTrue(xmlGuide.contains("## Output Scope"));
        assertTrue(xmlGuide.contains("## XML Rules"));
        assertTrue(xmlGuide.contains("Global Draw.io Layout Contract"));
        assertTrue(xmlGuide.contains("## Pre-flight Check"));
        assertTrue(xmlGuide.contains("All mxCell elements are siblings"));
        assertTrue(xmlGuide.contains("sourcePoint"));
        assertTrue(xmlGuide.contains("Hybrid routing"));
        assertTrue(xmlGuide.contains("Never stack opposite arrows"));

        assertTrue(visualDesign.contains("## Palette"));
        assertTrue(visualDesign.contains("#dae8fc"));
        assertTrue(visualDesign.contains("Modern Product Profile"));
        assertTrue(visualDesign.contains("complex diagrams may use 5-6"));
        assertTrue(visualDesign.contains("## House Patterns"));
        assertTrue(visualDesign.contains("## Golden Example"));
        assertTrue(visualDesign.contains("labelBackgroundColor=none"));
        assertTrue(visualDesign.contains("fillColor=none;dashed=1"));

        assertTrue(xmlGuide.length() <= MAX_SHARED_SKILL_CHARS);
        assertTrue(visualDesign.length() <= MAX_SHARED_SKILL_CHARS);
    }

    @Test
    public void everyDomainSkillIsCompactWithGoldenExampleAndChecklist() throws Exception {
        for (String skillName : DOMAIN_SKILLS) {
            String skill = readSkill(skillName);

            assertTrue(skillName + " must include a golden example section", skill.contains("## Golden Example"));
            assertTrue(skillName + " must embed example XML", skill.contains("```xml"));
            assertTrue(skillName + " must include a checklist", skill.contains("## Checklist"));
            assertTrue(skillName + " must stay compact (" + skill.length() + " chars)",
                    skill.length() <= MAX_DOMAIN_SKILL_CHARS);

            assertFalse(skillName + " must not restate the shared-responsibility meta prose",
                    skill.contains("Companion Shared Skills"));
            assertFalse(skillName + " must not restate shared output-scope rules",
                    skill.contains("## Output Scope"));
        }
    }

    @Test
    public void domainSkillsKeepTheirNotationResponsibilities() throws Exception {
        assertSkillContains("drawio-architecture", "View Selection", "context", "deployment", "runtime", "cylinder");
        assertSkillContains("drawio-flowchart", "Decision", "rhombus", "swimlane", "exception");
        assertSkillContains("drawio-sequence", "umlLifeline", "lifeline", "dashed", "return");
        assertSkillContains("drawio-er", "PK", "FK", "cardinality", "join table");
        assertSkillContains("drawio-uml", "generalization", "realization", "composition", "aggregation", "diamondThin");
        assertSkillContains("drawio-usecase", "umlActor", "include", "extend", "boundary");
        assertSkillContains("drawio-state", "Initial state", "Final state", "event [guard] / action");
    }

    private void assertSkillContains(String skillName, String... tokens) throws Exception {
        String skill = readSkill(skillName);
        for (String token : tokens) {
            assertTrue(skillName + " should contain token: " + token, skill.contains(token));
        }
    }

    private String readSkill(String skillName) throws Exception {
        return readResource("agent/skills/" + skillName + "/SKILL.md");
    }

    private String readResource(String path) throws Exception {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Missing test resource: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
