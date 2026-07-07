package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.analysis.CanvasAnalysis;
import org.zipp.ai.domain.agent.service.analysis.DefaultCanvasAnalyzer;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every skill ships a golden example that the drawer imitates. If an example itself has
 * structural or geometry problems, the agent learns broken habits — so each embedded XML
 * block must pass the same deterministic analysis applied to model output.
 */
public class DrawioGoldenExampleValidationTest {

    private static final List<String> SKILLS_WITH_EXAMPLES = List.of(
            "drawio-visual-design",
            "drawio-architecture",
            "drawio-flowchart",
            "drawio-uml",
            "drawio-sequence",
            "drawio-er",
            "drawio-usecase",
            "drawio-state",
            "drawio-concept"
    );

    private static final Pattern XML_BLOCK = Pattern.compile("```xml\\n(.*?)```", Pattern.DOTALL);

    private final DefaultCanvasAnalyzer analyzer = new DefaultCanvasAnalyzer();

    @Test
    public void everySkillGoldenExampleMustPassDeterministicAnalysis() throws Exception {
        for (String skillName : SKILLS_WITH_EXAMPLES) {
            String body = readSkill(skillName) + "\n" + readOptionalReference(skillName);
            List<String> examples = extractXmlBlocks(body);
            assertFalse(skillName + " must embed at least one ```xml golden example", examples.isEmpty());

            for (String example : examples) {
                String wrapped = "<mxGraphModel><root><mxCell id=\"0\"/><mxCell id=\"1\" parent=\"0\"/>"
                        + example + "</root></mxGraphModel>";
                CanvasAnalysis analysis = analyzer.analyze(wrapped, "unknown");
                assertTrue(skillName + " golden example must be free of critical/major issues but got: "
                                + analysis.getIssues().stream()
                                .map(issue -> "[" + issue.getSeverity() + "] " + issue.getMessage())
                                .toList(),
                        analysis.isValid());
            }
        }
    }

    private List<String> extractXmlBlocks(String body) {
        List<String> blocks = new ArrayList<>();
        Matcher matcher = XML_BLOCK.matcher(body);
        while (matcher.find()) {
            blocks.add(matcher.group(1));
        }
        return blocks;
    }

    private String readSkill(String skillName) throws Exception {
        String path = "agent/skills/" + skillName + "/SKILL.md";
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Missing test resource: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String readOptionalReference(String skillName) throws Exception {
        String path = "agent/skills/" + skillName + "/reference.md";
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            return is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
