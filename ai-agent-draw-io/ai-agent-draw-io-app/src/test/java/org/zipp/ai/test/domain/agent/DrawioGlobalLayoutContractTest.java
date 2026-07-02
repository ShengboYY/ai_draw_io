package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrawioGlobalLayoutContractTest {

    @Test
    public void shouldDefineGlobalLayoutContractOutsideArchitectureSkill() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");
        String visualDesignSkill = readResource("agent/skills/drawio-visual-design/SKILL.md");
        String architectureSkill = readResource("agent/skills/drawio-architecture/SKILL.md");

        assertTrue(agentPrompt.contains("Global Draw.io Layout Contract"));
        assertTrue(agentPrompt.contains("applies to every diagram type"));
        assertTrue(agentPrompt.contains("Do not rely on review repair to clean up first-draft routing"));
        assertTrue(agentPrompt.contains("Ports and waypoints are mandatory when a straight connector would cross"));

        assertTrue(visualDesignSkill.contains("Shared scope: these rules apply to every Draw.io diagram type"));
        assertTrue(visualDesignSkill.contains("Do not duplicate this shared contract or the XML patterns inside diagram-specific skills"));

        assertTrue(architectureSkill.contains("The shared Global Draw.io Layout Contract owns general spacing, ports, waypoints, text, and routing hygiene"));
    }

    @Test
    public void shouldExposeGlobalLayoutContractInMutationToolDescriptions() throws Exception {
        assertToolDescription("createDiagram", DrawioCanvasMcpService.DrawioXmlRequest.class);
        assertToolDescription("modifyDiagram", DrawioCanvasMcpService.ModifyDiagramRequest.class);
        assertToolDescription("optimizeDiagram", DrawioCanvasMcpService.OptimizeDiagramRequest.class);
    }

    @Test
    public void shouldKeepArchitectureSpecificBlueprintsOutOfGlobalDrawerPrompt() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");
        String architectureSkill = readResource("agent/skills/drawio-architecture/SKILL.md");

        assertFalse(agentPrompt.contains("For all architecture diagrams"));
        assertFalse(agentPrompt.contains("For architecture context diagrams"));
        assertFalse(agentPrompt.contains("For architecture container diagrams"));
        assertFalse(agentPrompt.contains("For JVM runtime diagrams"));
        assertFalse(agentPrompt.contains("architecture general blueprints"));
        assertFalse(agentPrompt.contains("JVM Runtime Adaptive Preset"));

        assertTrue(architectureSkill.contains("General Architecture View Contract"));
        assertTrue(architectureSkill.contains("Context Architecture Pattern"));
        assertTrue(architectureSkill.contains("Container Architecture Pattern"));
        assertTrue(architectureSkill.contains("JVM Runtime Adaptive Preset"));
        assertTrue(architectureSkill.contains("Keep `runtime` inside `drawio-architecture`"));
    }

    @Test
    public void shouldMergeReusableXmlPatternsIntoSharedVisualDesignSkill() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");
        String visualDesignSkill = readResource("agent/skills/drawio-visual-design/SKILL.md");

        assertTrue(agentPrompt.contains("reusable Draw.io XML patterns"));
        assertTrue(agentPrompt.contains("drawio-visual-design owns shared visual style and reusable Draw.io XML patterns"));
        assertTrue(visualDesignSkill.contains("Draw.io XML Patterns"));
        assertTrue(visualDesignSkill.contains("edgeStyle=orthogonalEdgeStyle"));
        assertTrue(visualDesignSkill.contains("edgeStyle=elbowEdgeStyle;elbow=vertical"));
        assertTrue(visualDesignSkill.contains("jumpStyle=arc;jumpSize=10"));
        assertTrue(visualDesignSkill.contains("<Array as=\"points\">"));
        assertTrue(visualDesignSkill.contains("labelBackgroundColor=none;labelBorderColor=none"));
        assertTrue(visualDesignSkill.contains("parent=\"<container-id>\""));
        assertFalse(agentPrompt.contains("drawio-xml-guide"));
    }

    private String readResource(String path) throws Exception {
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Missing test resource: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void assertToolDescription(String methodName, Class<?> requestType) throws Exception {
        Tool tool = DrawioCanvasMcpService.class.getMethod(methodName, requestType).getAnnotation(Tool.class);

        assertTrue(tool.description().contains("Global Draw.io Layout Contract"));
        assertTrue(tool.description().contains("explicit exit/entry ports"));
        assertTrue(tool.description().contains("orthogonal routing"));
    }
}
