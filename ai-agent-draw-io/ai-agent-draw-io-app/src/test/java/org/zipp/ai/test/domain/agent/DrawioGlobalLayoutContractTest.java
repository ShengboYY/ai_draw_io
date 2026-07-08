package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasMcpService;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Global Draw.io Layout Contract lives in the always-injected drawio-xml-guide skill;
 * the drawer prompt references it instead of restating it, and tool descriptions keep
 * pointing at it so every mutation call carries the reminder.
 */
public class DrawioGlobalLayoutContractTest {

    @Test
    public void layoutContractLivesInXmlGuideNotInDrawerPrompt() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");
        String xmlGuide = readResource("agent/skills/drawio-xml-guide/SKILL.md");

        assertTrue(xmlGuide.contains("Global Draw.io Layout Contract"));
        assertTrue(xmlGuide.contains("One reading direction per diagram"));
        assertTrue(xmlGuide.contains("orthogonalEdgeStyle"));
        assertTrue(xmlGuide.contains("Never corner ports"));
        assertTrue(xmlGuide.contains("relationship/layout semantics"));
        assertTrue(xmlGuide.contains("## Pre-flight Check"));

        // The contract offers two first-class layout modes: the model picks radial from the
        // content shape (no user command needed), and the free-routed edge marker is spelled out.
        assertTrue(xmlGuide.contains("grid-flow"));
        assertTrue(xmlGuide.contains("radial"));
        assertTrue(xmlGuide.contains("edgeStyle=none"));
        assertTrue(xmlGuide.contains("curved=1"));

        // The drawer prompt references the contract but no longer duplicates its rules.
        assertTrue(agentPrompt.contains("Global Draw.io Layout Contract"));
        assertFalse(agentPrompt.contains("Use deterministic grid placement"));
        assertFalse(agentPrompt.contains("Viewport discipline"));
        assertFalse(agentPrompt.contains("Never let multiple edges share the same path"));
    }

    @Test
    public void shouldExposeGlobalLayoutContractInMutationToolDescriptions() throws Exception {
        assertToolDescription("createDiagram", DrawioCanvasMcpService.DrawioXmlRequest.class);
        assertToolDescription("modifyDiagram", DrawioCanvasMcpService.ModifyDiagramRequest.class);
        assertToolDescription("optimizeDiagram", DrawioCanvasMcpService.OptimizeDiagramRequest.class);
    }

    @Test
    public void drawerPromptStaysDiagramTypeNeutral() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertFalse(agentPrompt.contains("For all architecture diagrams"));
        assertFalse(agentPrompt.contains("For architecture context diagrams"));
        assertFalse(agentPrompt.contains("For JVM runtime diagrams"));
        assertFalse(agentPrompt.contains("JVM Runtime Adaptive Preset"));

        // Domain guidance is delegated to the skill stack.
        assertTrue(agentPrompt.contains("drawio-xml-guide"));
        assertTrue(agentPrompt.contains("drawio-visual-design"));
        assertTrue(agentPrompt.contains("golden example"));
    }

    @Test
    public void routerAndDrawerKnowTheRadialConceptSkill() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        // The router can reach radial layouts without the user asking for "circular":
        // concept is a routable diagram type mapped to the drawio-concept skill.
        assertTrue(agentPrompt.contains("concept / drawio-concept"));
        assertTrue(agentPrompt.contains("usecase|state|concept"));
        assertTrue(agentPrompt.contains("drawio-concept (onion/ring models"));
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
        assertTrue(tool.description().contains("relationship/layout semantics"));
        assertTrue(tool.description().contains("edgeStyle=none"));
        assertTrue(tool.description().contains("same node-side anchor"));
        assertTrue(tool.description().contains("explicit exit/entry ports"));
        assertTrue(tool.description().contains("orthogonal routing"));
    }
}
