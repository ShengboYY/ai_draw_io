package org.zipp.ai.test.domain.agent;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrawioSkillResponsibilityResourceTest {

    private static final List<String> DOMAIN_SKILLS = List.of(
            "drawio-architecture",
            "drawio-flowchart",
            "drawio-sequence",
            "drawio-er",
            "drawio-uml",
            "drawio-usecase",
            "drawio-state"
    );

    @Test
    public void shouldKeepReusableVisualAndXmlRulesInSharedSkill() throws Exception {
        String visualDesignSkill = readSkill("drawio-visual-design");

        assertTrue(visualDesignSkill.contains("## 4. Shared Profile Handoff"));
        assertTrue(visualDesignSkill.contains("Draw.io XML Patterns"));
        assertTrue(visualDesignSkill.contains("edgeStyle=orthogonalEdgeStyle"));
        assertTrue(visualDesignSkill.contains("edgeStyle=elbowEdgeStyle;elbow=vertical"));
        assertTrue(visualDesignSkill.contains("jumpStyle=arc;jumpSize=10"));
        assertTrue(visualDesignSkill.contains("<Array as=\"points\">"));
        assertTrue(visualDesignSkill.contains("labelBackgroundColor=none;labelBorderColor=none"));
        assertTrue(visualDesignSkill.contains("parent=\"<container-id>\""));

        assertFalse(visualDesignSkill.contains("### 4.1 Architecture Diagrams"));
        assertFalse(visualDesignSkill.contains("### 4.2 Flowcharts"));
        assertFalse(visualDesignSkill.contains("### 4.3 Sequence Diagrams"));
        assertFalse(visualDesignSkill.contains("JVM Runtime Adaptive Preset"));
    }

    @Test
    public void shouldMakeEveryDomainSkillReferenceSharedContractInsteadOfRestatingXmlRoutingRules() throws Exception {
        for (String skillName : DOMAIN_SKILLS) {
            String skill = readSkill(skillName);

            assertTrue(skillName + " should reference the shared visual design skill",
                    skill.contains("Always use this skill together with `drawio-visual-design`."));
            assertTrue(skillName + " should declare its responsibility boundary",
                    skill.contains("Shared visual/XML/layout contract: use `drawio-visual-design`"));
            assertTrue(skillName + " should point generic routing and spacing to the shared skill",
                    skill.contains("Do not repeat generic connector routing, spacing, transparent label, waypoint, or container-parent XML rules here."));

            assertFalse(skillName + " should not duplicate the full orthogonal connector recipe",
                    skill.contains("edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto"));
            assertFalse(skillName + " should not duplicate transparent edge label XML",
                    skill.contains("labelBackgroundColor=none;labelBorderColor=none"));
            assertFalse(skillName + " should not duplicate jump marker XML",
                    skill.contains("jumpStyle=arc"));
            assertFalse(skillName + " should not duplicate waypoint XML",
                    skill.contains("<Array as=\"points\">"));
            assertFalse(skillName + " should not carry hard-coded generic spacing thresholds",
                    skill.matches("(?s).*Keep horizontal spacing >= \\d+.*"));
        }
    }

    @Test
    public void shouldPreserveDomainSpecificResponsibilitiesInEachSkill() throws Exception {
        assertSkillContains("drawio-architecture", "C4", "deployment", "Runtime Architecture Pattern", "JVM Runtime Adaptive Preset");
        assertSkillContains("drawio-flowchart", "Main Flow Rules", "Branch Rules", "Loop Rules", "Exception Path Rules");
        assertSkillContains("drawio-sequence", "lifelines", "messages", "Activation", "Combined Fragments");
        assertSkillContains("drawio-er", "primary keys", "foreign keys", "cardinality", "join tables");
        assertSkillContains("drawio-uml", "attributes", "methods", "inheritance", "composition");
        assertSkillContains("drawio-usecase", "actors", "system boundary", "<<include>>", "<<extend>>");
        assertSkillContains("drawio-state", "stable conditions", "events", "guards", "initial state", "final state");
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
