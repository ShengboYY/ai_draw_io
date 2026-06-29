package org.zipp.ai.test.domain.agent;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrawioArchitectureSkillResourceTest {

    @Test
    public void shouldKeepRuntimeAsArchitectureSubtypeWithLayoutPattern() throws Exception {
        String architectureSkill = readResource("agent/skills/drawio-architecture/SKILL.md");
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(architectureSkill.contains("Runtime Architecture Pattern"));
        assertTrue(architectureSkill.contains("thread-private"));
        assertTrue(architectureSkill.contains("execution engine"));
        assertTrue(architectureSkill.contains("legend"));
        assertTrue(agentPrompt.contains("runtime stays inside architecture"));
    }

    @Test
    public void shouldDescribeArchitectureGeneralAndSubtypePatterns() throws Exception {
        String architectureSkill = readResource("agent/skills/drawio-architecture/SKILL.md");
        String visualDesignSkill = readResource("agent/skills/drawio-visual-design/SKILL.md");
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(architectureSkill.contains("General Architecture View Contract"));
        assertTrue(architectureSkill.contains("Context Architecture Pattern"));
        assertTrue(architectureSkill.contains("Container Architecture Pattern"));
        assertTrue(architectureSkill.contains("Component Architecture Pattern"));
        assertTrue(architectureSkill.contains("Deployment Architecture Pattern"));
        assertTrue(architectureSkill.contains("Dynamic Architecture Pattern"));
        assertTrue(architectureSkill.contains("Integration And Data Architecture Pattern"));
        assertTrue(visualDesignSkill.contains("architecture view contract"));
        assertTrue(agentPrompt.contains("architecture general blueprints"));
    }

    @Test
    public void shouldUseAdaptiveLayoutPresetsInsteadOfExactTemplateCopies() throws Exception {
        String architectureSkill = readResource("agent/skills/drawio-architecture/SKILL.md");
        String visualDesignSkill = readResource("agent/skills/drawio-visual-design/SKILL.md");
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(architectureSkill.contains("Adaptive Layout Presets"));
        assertTrue(architectureSkill.contains("Do not copy templates exactly"));
        assertTrue(architectureSkill.contains("JVM Runtime Adaptive Preset"));
        assertTrue(architectureSkill.contains("slots"));
        assertTrue(architectureSkill.contains("Expansion rules"));
        assertTrue(visualDesignSkill.contains("layout presets are adaptable skeletons"));
        assertTrue(agentPrompt.contains("closest adaptive layout preset"));
        assertTrue(agentPrompt.contains("Do not copy templates exactly"));
    }

    @Test
    public void shouldExposeTaskTypeInDrawingWorkflowPrompts() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("taskType"));
        assertTrue(agentPrompt.contains("display_diagram"));
        assertTrue(agentPrompt.contains("append_diagram"));
        assertTrue(agentPrompt.contains("edit_diagram"));
        assertTrue(agentPrompt.contains("optimize_diagram"));
        assertTrue(agentPrompt.contains("drawioCanvasToolCallbackProvider"));
        assertTrue(agentPrompt.contains("Prefer actual registered tool calls over text output"));
        assertTrue(agentPrompt.contains("Only output the final drawio_done JSON after validate_diagram returns valid=true"));
        assertTrue(agentPrompt.contains("approved=true, do not call any drawing tool"));
        assertTrue(agentPrompt.contains("patch_existing"));
        assertTrue(agentPrompt.contains("drawing tool call -> review/direct repair loop"));
    }

    @Test
    public void shouldBorrowNextDrawioQualityRulesForToolDrawing() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("Generate only mxCell elements for display_diagram"));
        assertTrue(agentPrompt.contains("All mxCell elements must be siblings"));
        assertTrue(agentPrompt.contains("Viewport discipline"));
        assertTrue(agentPrompt.contains("Always specify exitX, exitY, entryX, and entryY"));
        assertTrue(agentPrompt.contains("Never let multiple edges share the same path"));
        assertTrue(agentPrompt.contains("Route edges around obstacle shapes"));
        assertTrue(agentPrompt.contains("No XML comments"));
        assertTrue(agentPrompt.contains("critical visual blockers"));
    }

    @Test
    public void shouldClarifyEdgePortTokensMustStayInsideStyleAttributes() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("exitX=1;exitY=0.5;entryX=0;entryY=0.5;"));
        assertTrue(agentPrompt.contains("Never write <exitX"));
        assertTrue(agentPrompt.contains("Do not write exitX, exitY, entryX, or entryY as mxCell attributes"));
    }

    @Test
    public void shouldRequireExecutableReviewStrategyAndMinimalRepairPrompts() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("\"fix_strategy\":\"local_edit|route_only|append_only|layout_optimize|full_redraw\""));
        assertTrue(agentPrompt.contains("\"suggested_tool\":\"update_cells|edit_diagram|append_diagram|route_edges|optimize_diagram|display_diagram\""));
        assertTrue(agentPrompt.contains("local_edit -> update_cells"));
        assertTrue(agentPrompt.contains("route_only -> route_edges"));
        assertTrue(agentPrompt.contains("append_only -> append_diagram"));
        assertTrue(agentPrompt.contains("layout_optimize -> optimize_diagram"));
        assertTrue(agentPrompt.contains("full_redraw -> display_diagram"));
        assertTrue(agentPrompt.contains("Do not redraw the entire diagram unless fix_strategy=full_redraw"));
        assertTrue(agentPrompt.contains("Prefer local_edit, route_only, append_only, or layout_optimize over full_redraw"));
    }

    @Test
    public void shouldExposeP1CanvasToolsInDrawingPrompt() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("validate_diagram"));
        assertTrue(agentPrompt.contains("get_canvas_state"));
        assertTrue(agentPrompt.contains("find_cells"));
        assertTrue(agentPrompt.contains("update_cells"));
        assertTrue(agentPrompt.contains("Before local edits, use find_cells when the target id is uncertain"));
        assertTrue(agentPrompt.contains("After each drawing mutation, use validate_diagram"));
        assertTrue(agentPrompt.contains("If validate_diagram returns valid=false"));
    }

    @Test
    public void shouldExposeP2AndP3LayoutQualityToolsInDrawingPrompt() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("detect_overlaps"));
        assertTrue(agentPrompt.contains("route_edges"));
        assertTrue(agentPrompt.contains("Use detect_overlaps when validation or review mentions overlapping nodes"));
        assertTrue(agentPrompt.contains("Use route_edges for route_only feedback"));
        assertTrue(agentPrompt.contains("Do not load or apply unrelated diagram skill rules"));
        assertTrue(agentPrompt.contains("Only apply the selected skillName plus drawio-visual-design"));
    }

    @Test
    public void shouldExposeLongXmlContinuationProtocolInDrawingPrompt() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("continue_diagram"));
        assertTrue(agentPrompt.contains("Use continue_diagram when the final XML would be too long"));
        assertTrue(agentPrompt.contains("Use the same continuationId for every fragment"));
        assertTrue(agentPrompt.contains("Set done=false for intermediate fragments"));
        assertTrue(agentPrompt.contains("Set done=true only on the final fragment"));
        assertTrue(agentPrompt.contains("Do not truncate XML"));
    }

    @Test
    public void shouldUseRoutedToolGateAndReviewBudgetInPrompts() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("[Intent Routing Result].allowedTools is the per-turn tool gate"));
        assertTrue(agentPrompt.contains("Use only those tools"));
        assertTrue(agentPrompt.contains("[Intent Routing Result].maxReviewIterations is the review repair budget"));
        assertTrue(agentPrompt.contains("If it is 0, do the initial drawing action only"));
    }

    @Test
    public void shouldTreatValidationFailuresAsReviewRepairSignals() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("Treat the latest validation_result with valid=false as automatic repair feedback"));
        assertTrue(agentPrompt.contains("Reject validation_result valid=false even when the XML is parseable"));
        assertTrue(agentPrompt.contains("major visual issues such as overlaps, opaque labels, or crowded layout"));
    }

    @Test
    public void shouldNotUseMissingReviewResultAsRequiredAdkTemplateVariable() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertFalse(agentPrompt.contains("{review_result}"));
        assertFalse(agentPrompt.contains("{draft_diagram}"));
        assertTrue(agentPrompt.contains("review_result"));
        assertTrue(agentPrompt.contains("draft_diagram"));
    }

    private String readResource(String path) throws Exception {
        // Read packaged resources so the test verifies what the running app loads.
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (null == inputStream) {
                throw new IllegalArgumentException("Missing resource: " + path);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
