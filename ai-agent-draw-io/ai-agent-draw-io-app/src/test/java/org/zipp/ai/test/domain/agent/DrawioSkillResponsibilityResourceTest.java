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

    @Test
    public void umlSkillUsesDrawioLibraryClassCellsAndStraightConnectors() throws Exception {
        String skill = readSkill("drawio-uml");

        assertTrue(skill.contains("swimlane;fontStyle=0;childLayout=stackLayout"));
        assertTrue(skill.contains("resizeParent=1"));
        assertTrue(skill.contains("portConstraint=eastwest"));
        assertTrue(skill.contains("edgeStyle=none"));
        // Native UML classes use stacked child rows, not one rectangle with HTML rules.
        assertFalse(skill.contains("&lt;hr&gt;"));
    }

    @Test
    public void umlSkillShapesModelsBeforeRoutingRelationships() throws Exception {
        String skill = readSkill("drawio-uml");

        assertTrue(skill.contains("Model shaping before routing"));
        assertTrue(skill.contains("Pick one intent"));
        assertTrue(skill.contains("5-9 core classes"));
        assertTrue(skill.contains("bounded context/package"));
        assertTrue(skill.contains("database field checklist"));
        assertTrue(skill.contains("verb-only"));
        assertTrue(skill.contains("type-only dependency"));
        assertTrue(skill.contains("subclasses directly below"));
        assertTrue(skill.contains("organization/support classes"));
        assertTrue(skill.contains("long diagonal"));
    }

    @Test
    public void umlSkillAvoidsInventedSupportRelationsAndInheritanceCrowding() throws Exception {
        String skill = readSkill("drawio-uml");

        assertTrue(skill.contains("Do not invent support/admin relationships"));
        assertTrue(skill.contains("Diamonds are only for part-whole"));
        assertTrue(skill.contains("plain associations for manages/employs/borrows/issues"));
        assertTrue(skill.contains("Reserve a clear inheritance row"));
        assertTrue(skill.contains("Keep non-subclass classes out of the subclass row"));
        assertTrue(skill.contains("section heights must fit their text"));
        assertTrue(skill.contains("leave bottom padding"));
        assertTrue(skill.contains("inheritance arrows attach to the class border"));
    }

    @Test
    public void umlSkillKeepsRelationshipLabelsReadable() throws Exception {
        String skill = readSkill("drawio-uml");

        assertTrue(skill.contains("Labeled relationships need readable edge length"));
        assertTrue(skill.contains("80-120 px of clear line"));
        assertTrue(skill.contains("move classes farther apart"));
        assertTrue(skill.contains("label collides with nodes"));
        assertTrue(skill.contains("omit weak labels"));
    }

    @Test
    public void umlSkillBalancesReadableLabelsWithCompactClusters() throws Exception {
        String skill = readSkill("drawio-uml");

        assertTrue(skill.contains("Readable labels must not create an over-wide diagram"));
        assertTrue(skill.contains("keep related class gaps compact"));
        assertTrue(skill.contains("160-260 px border-to-border"));
        assertTrue(skill.contains("local clusters"));
        assertTrue(skill.contains("Shorten verbose labels"));
        assertTrue(skill.contains("exact cardinality near endpoints"));
    }

    @Test
    public void erSkillUsesDrawioTableRowsAndCrowFootConnectors() throws Exception {
        String skill = readSkill("drawio-er");

        assertTrue(skill.contains("Entity Relation table/list"));
        assertTrue(skill.contains("swimlane;childLayout=stackLayout"));
        assertTrue(skill.contains("points=[[0,0.5],[1,0.5]]"));
        assertTrue(skill.contains("portConstraint=eastwest"));
        assertTrue(skill.contains("ERone"));
        assertTrue(skill.contains("ERmany"));
        assertTrue(skill.contains("edgeStyle=none"));
        // ER tables should be structured rows, not one rectangle with HTML rules.
        assertFalse(skill.contains("&lt;hr&gt;"));
    }

    @Test
    public void routingGuidanceChoosesLineShapeByRelationshipSemantics() throws Exception {
        String xmlGuide = readSkill("drawio-xml-guide");
        String architecture = readSkill("drawio-architecture");
        String flowchart = readSkill("drawio-flowchart");

        assertTrue(xmlGuide.contains("relationship/layout semantics"));
        assertTrue(xmlGuide.contains("hierarchy"));
        assertTrue(xmlGuide.contains("fan-out"));
        assertTrue(xmlGuide.contains("straight"));
        assertTrue(xmlGuide.contains("edgeStyle=none"));
        assertTrue(xmlGuide.contains("dense routing"));
        assertTrue(xmlGuide.contains("network wiring"));
        assertTrue(xmlGuide.contains("swimlane traffic"));
        assertTrue(xmlGuide.contains("Never reuse the same node-side anchor"));
        assertTrue(xmlGuide.contains("spread them to distinct tracks"));
        assertTrue(xmlGuide.contains("Do not solve anchor conflicts by adding long detours"));

        assertTrue(architecture.contains("hierarchy/fan-out"));
        assertTrue(architecture.contains("edgeStyle=none"));
        assertTrue(architecture.contains("cloud/Kubernetes/network"));

        assertTrue(flowchart.contains("top-down"));
        assertTrue(flowchart.contains("decision tree"));
        assertTrue(flowchart.contains("orthogonal"));
    }

    @Test
    public void stateSkillSpellsOutSpecialStateShapes() throws Exception {
        String skill = readSkill("drawio-state");

        assertTrue(skill.contains("Choice diamond"));
        assertTrue(skill.contains("rhombus"));
        assertTrue(skill.contains("Fork/join bars"));
        assertTrue(skill.contains("fillColor=#666666;strokeColor=#666666"));
        assertTrue(skill.contains("Composite states"));
        assertTrue(skill.contains("container=1;collapsible=0"));
    }

    @Test
    public void flowchartSkillKeepsRetryBranchesLocal() throws Exception {
        String skill = readSkill("drawio-flowchart");

        assertTrue(skill.contains("Retry/correction branches"));
        assertTrue(skill.contains("nearest input/action"));
        assertTrue(skill.contains("Do not centralize unrelated failures"));
        assertTrue(skill.contains("duplicate a small correction step"));
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
