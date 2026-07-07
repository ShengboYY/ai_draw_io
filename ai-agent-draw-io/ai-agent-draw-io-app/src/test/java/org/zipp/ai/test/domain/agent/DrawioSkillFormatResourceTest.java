package org.zipp.ai.test.domain.agent;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Keeps built-in Draw.io skills machine-readable so section-aware truncation has stable anchors.
 */
public class DrawioSkillFormatResourceTest {

    private static final Pattern FRONTMATTER = Pattern.compile("\\A---\\R(.*?)\\R---\\R", Pattern.DOTALL);
    private static final Pattern H2 = Pattern.compile("(?m)^##\\s+(.+)$");
    private static final Pattern PRIORITY = Pattern.compile("\\[(P0|P1)]\\s*$");
    private static final Pattern GOLDEN_EXAMPLE_P0 = Pattern.compile("(?m)^## Golden Example.*\\[P0]$");
    private static final Pattern CHECKLIST_P1 = Pattern.compile("(?m)^## Checklist \\[P1]$");
    private static final String ROUTER_SKILL_CATEGORY = "drawio-design";
    private static final String SHARED_XML_GUIDE_SKILL = "drawio-xml-guide";
    private static final String SHARED_SKILL = "drawio-visual-design";

    private static final Map<String, String> DIAGRAM_TYPES = new LinkedHashMap<>();
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

    static {
        DIAGRAM_TYPES.put("drawio-architecture", "architecture");
        DIAGRAM_TYPES.put("drawio-flowchart", "flowchart");
        DIAGRAM_TYPES.put("drawio-sequence", "sequence");
        DIAGRAM_TYPES.put("drawio-er", "er");
        DIAGRAM_TYPES.put("drawio-uml", "uml_class");
        DIAGRAM_TYPES.put("drawio-usecase", "usecase");
        DIAGRAM_TYPES.put("drawio-state", "state");
        DIAGRAM_TYPES.put("drawio-concept", "concept");
        DIAGRAM_TYPES.put("drawio-xml-guide", "shared");
        DIAGRAM_TYPES.put("drawio-visual-design", "shared");
    }

    @Test
    public void everyDrawioSkillUsesNormalizedFrontmatter() throws Exception {
        for (Map.Entry<String, String> entry : DIAGRAM_TYPES.entrySet()) {
            SkillFile skill = readSkill(entry.getKey());

            assertEquals(entry.getKey(), skill.frontmatter().get("name"));
            assertEquals("1", skill.frontmatter().get("schemaVersion"));
            assertEquals(ROUTER_SKILL_CATEGORY, skill.frontmatter().get("category"));
            assertEquals(entry.getValue(), skill.frontmatter().get("diagramType"));
        }
    }

    @Test
    public void sharedSkillsAreNotRouterSelectable() throws Exception {
        assertEquals("false", readSkill(SHARED_XML_GUIDE_SKILL)
                .frontmatter().get("selectable"));
        assertEquals("false", readSkill(SHARED_SKILL)
                .frontmatter().get("selectable"));
    }

    @Test
    public void everySecondLevelSectionDeclaresPriority() throws Exception {
        for (String skillName : DIAGRAM_TYPES.keySet()) {
            String body = readSkill(skillName).body();
            assertSecondLevelPriorities(skillName + "/SKILL.md", body);
            String referenceBody = readOptionalResource("agent/skills/" + skillName + "/reference.md");
            if (referenceBody != null) {
                assertSecondLevelPriorities(skillName + "/reference.md", referenceBody);
            }
        }
    }

    @Test
    public void visualDesignKeepsHeavyExampleInReferenceFile() throws Exception {
        String skillBody = readSkill(SHARED_SKILL).body();
        String referenceBody = readOptionalResource("agent/skills/" + SHARED_SKILL + "/reference.md");

        assertTrue("drawio-visual-design SKILL.md should point to section-level reference lookup",
                skillBody.contains("get_drawio_skill_section"));
        assertTrue("drawio-visual-design reference.md must keep the full XML example",
                referenceBody != null && referenceBody.contains("<mxCell id=\"2\" value=\"AI Diagram System\""));
    }

    private void assertSecondLevelPriorities(String label, String body) {
        Matcher matcher = H2.matcher(body);
        int count = 0;
        while (matcher.find()) {
            count++;
            String title = matcher.group(1).trim();
            assertTrue(label + " section must end with [P0] or [P1]: " + title,
                    PRIORITY.matcher(title).find());
        }
        assertTrue(label + " must have at least one second-level section", count > 0);
    }

    @Test
    public void domainSkillsKeepRoutingAndBudgetAnchors() throws Exception {
        for (String skillName : DOMAIN_SKILLS) {
            String body = readSkill(skillName).body();

            assertTrue(skillName + " must declare when to use it", body.contains("## When To Use [P0]"));
            assertTrue(skillName + " must keep a priority golden example",
                    GOLDEN_EXAMPLE_P0.matcher(body).find());
            assertTrue(skillName + " must keep a lower-priority checklist", CHECKLIST_P1.matcher(body).find());
        }
    }

    private SkillFile readSkill(String skillName) throws Exception {
        String raw = readResource("agent/skills/" + skillName + "/SKILL.md");
        Matcher matcher = FRONTMATTER.matcher(raw);
        assertTrue(skillName + " must start with YAML frontmatter", matcher.find());
        return new SkillFile(parseTopLevelFrontmatter(matcher.group(1)), raw.substring(matcher.end()));
    }

    private Map<String, String> parseTopLevelFrontmatter(String frontmatter) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : frontmatter.split("\\R")) {
            if (line.isBlank() || Character.isWhitespace(line.charAt(0))) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            values.put(key, value.replaceAll("^\"|\"$", ""));
        }
        return values;
    }

    private String readResource(String path) throws Exception {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Missing test resource: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readOptionalResource(String path) throws Exception {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            return is == null ? null : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record SkillFile(Map<String, String> frontmatter, String body) {
    }
}
