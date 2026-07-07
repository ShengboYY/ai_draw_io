package org.zipp.ai.test.domain.agent;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Locks the drawer prompt/architecture-skill split: the prompt owns routing and tool protocol,
 * the architecture skill owns view selection and its golden example.
 */
public class DrawioArchitectureSkillResourceTest {

    @Test
    public void architectureSkillOwnsViewSelectionAndGoldenExample() throws Exception {
        String architectureSkill = readResource("agent/skills/drawio-architecture/SKILL.md");

        assertTrue(architectureSkill.contains("## View Selection"));
        assertTrue(architectureSkill.contains("context"));
        assertTrue(architectureSkill.contains("container"));
        assertTrue(architectureSkill.contains("deployment"));
        assertTrue(architectureSkill.contains("runtime"));
        assertTrue(architectureSkill.contains("Never mix levels"));
        assertTrue(architectureSkill.contains("cylinder"));
        assertTrue(architectureSkill.contains("## Golden Example"));
        assertTrue(architectureSkill.contains("```xml"));
    }

    @Test
    public void drawerPromptMapsRouteTypesToRegisteredTools() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("routeType=create_new -> create_diagram"));
        assertTrue(agentPrompt.contains("routeType=edit_existing -> modify_diagram only"));
        assertTrue(agentPrompt.contains("routeType=optimize_layout -> optimize_diagram"));
        assertTrue(agentPrompt.contains("drawioCanvasToolCallbackProvider"));
        assertTrue(agentPrompt.contains("drawioSkillToolCallbackProvider"));
        assertTrue(agentPrompt.contains("get_drawio_skill"));
        assertTrue(agentPrompt.contains("MUST use registered tool calls when they are available"));
        assertTrue(agentPrompt.contains("set targetLabel"));
        assertFalse(agentPrompt.contains("inspect_canvas"));
        assertFalse(agentPrompt.contains("Skill stack (injected below"));
    }

    @Test
    public void intentRouterTreatsAvailableSkillsAsAuthoritativeDynamicCatalog() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("[Available Skills] is the authoritative skill catalog"));
        assertTrue(agentPrompt.contains("including platform and user-added skills"));
        assertTrue(agentPrompt.contains("even when it is not one of the built-in examples below"));
        assertTrue(agentPrompt.contains("built-in mapping below only as a fallback"));
    }

    @Test
    public void drawerPromptDefinesSelfRepairLoopProtocol() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("Repair loop protocol:"));
        assertTrue(agentPrompt.contains("repairBrief"));
        assertTrue(agentPrompt.contains("maxRepairRounds"));
        assertTrue(agentPrompt.contains("Repair turns never call create_diagram"));
        assertTrue(agentPrompt.contains("Stop calling tools when repairBrief says to finish"));
        // The old reviewer/repair handoff protocol must be gone.
        assertFalse(agentPrompt.contains("review_result"));
        assertFalse(agentPrompt.contains("fix_strategy"));
        assertFalse(agentPrompt.contains("reviewRepairTools"));
    }

    @Test
    public void drawerPromptKeepsFallbackNdjsonForms() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("{\"type\":\"create_diagram\",\"xml\":"));
        assertTrue(agentPrompt.contains("{\"type\":\"modify_diagram\",\"mode\":\"patch\""));
        assertTrue(agentPrompt.contains("{\"type\":\"modify_diagram\",\"mode\":\"append\""));
        assertTrue(agentPrompt.contains("{\"type\":\"modify_diagram\",\"mode\":\"replace_cells\""));
        assertTrue(agentPrompt.contains("{\"type\":\"optimize_diagram\",\"mode\":\"route_only\""));
        assertTrue(agentPrompt.contains("{\"type\":\"optimize_diagram\",\"mode\":\"layout_optimize\""));
    }

    @Test
    public void drawingWorkflowIsASingleSelfRepairingDrawer() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("name: sequential_draw_process"));
        assertTrue(agentPrompt.contains("- agent_drawer"));
        assertFalse(agentPrompt.contains("agent_repair_drawer"));
        assertFalse(agentPrompt.contains("agent_reviewer"));
        assertFalse(agentPrompt.contains("loop_refinement"));
        assertFalse(agentPrompt.contains("parallel_generation"));
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
