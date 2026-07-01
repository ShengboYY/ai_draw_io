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
        assertTrue(agentPrompt.contains("create_diagram"));
        assertTrue(agentPrompt.contains("modify_diagram"));
        assertTrue(agentPrompt.contains("optimize_diagram"));
        assertFalse(agentPrompt.contains("inspect_canvas"));
        assertTrue(agentPrompt.contains("drawioCanvasToolCallbackProvider"));
        assertTrue(agentPrompt.contains("MUST use actual registered tool calls when they are available"));
        assertTrue(agentPrompt.contains("Backend automatically injects [Canvas Issues]"));
        assertTrue(agentPrompt.contains("approved=true, do not call any drawing tool"));
        assertTrue(agentPrompt.contains("edit_existing"));
        assertTrue(agentPrompt.contains("edit_existing -> modify_diagram"));
        assertFalse(agentPrompt.contains("patch_existing -> modify_diagram"));
        assertTrue(agentPrompt.contains("drawing tool call -> review/direct repair loop"));
    }

    @Test
    public void shouldBorrowNextDrawioQualityRulesForToolDrawing() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("Generate only mxCell elements for create_diagram"));
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
        assertTrue(agentPrompt.contains("\"suggested_tool\":\"modify_diagram|optimize_diagram|create_diagram\""));
        assertTrue(agentPrompt.contains("local_edit -> modify_diagram mode=patch or replace_cells"));
        assertTrue(agentPrompt.contains("route_only -> optimize_diagram"));
        assertTrue(agentPrompt.contains("append_only -> modify_diagram mode=append"));
        assertFalse(agentPrompt.contains("append_only -> modify_diagram mode=full_xml"));
        assertTrue(agentPrompt.contains("layout_optimize -> optimize_diagram"));
        assertTrue(agentPrompt.contains("{\"type\":\"modify_diagram\",\"mode\":\"append\""));
        assertTrue(agentPrompt.contains("full_redraw -> create_diagram"));
        assertTrue(agentPrompt.contains("Do not redraw the entire diagram unless fix_strategy=full_redraw"));
        assertTrue(agentPrompt.contains("Prefer local_edit, route_only, append_only, or layout_optimize over full_redraw"));
    }

    @Test
    public void shouldExposeP1CanvasToolsInDrawingPrompt() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("Backend automatically injects [Canvas Issues]"));
        assertTrue(agentPrompt.contains("modify_diagram is for edit_existing"));
        assertFalse(agentPrompt.contains("inspect_canvas"));
        assertFalse(agentPrompt.contains("After each drawing mutation, use inspect_canvas"));
        assertFalse(agentPrompt.contains("Only output the final drawio_done JSON after inspect_canvas returns valid=true"));
        assertTrue(agentPrompt.contains("set targetLabel on modify_diagram"));
    }

    @Test
    public void shouldExposeP2AndP3LayoutQualityToolsInDrawingPrompt() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertFalse(agentPrompt.contains("inspect_canvas"));
        assertTrue(agentPrompt.contains("optimize_diagram is for optimize_layout and route_only review feedback"));
        assertTrue(agentPrompt.contains("For route_only, call optimize_diagram"));
        assertTrue(agentPrompt.contains("mode=route_only"));
        assertTrue(agentPrompt.contains("For optimize_diagram mode=route_only"));
        assertTrue(agentPrompt.contains("For optimize_diagram mode=route_only, prefer userId and diagramId from [Canvas State]"));
        assertFalse(agentPrompt.contains("For modify_diagram mode=full_xml and optimize_diagram, pass a complete updated mxGraphModel"));
        assertTrue(agentPrompt.contains("Do not load or apply unrelated diagram skill rules"));
        assertTrue(agentPrompt.contains("Only apply the selected skillName plus drawio-visual-design"));
    }

    @Test
    public void shouldExposeConsolidatedFallbackProtocolInDrawingPrompt() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("Fallback NDJSON format when registered tool calls are unavailable"));
        assertTrue(agentPrompt.contains("{\"type\":\"create_diagram\""));
        assertTrue(agentPrompt.contains("{\"type\":\"modify_diagram\",\"mode\":\"patch\""));
        assertTrue(agentPrompt.contains("{\"type\":\"modify_diagram\",\"mode\":\"full_xml\""));
        assertTrue(agentPrompt.contains("{\"type\":\"optimize_diagram\""));
        assertFalse(agentPrompt.contains("Use continue_diagram when the final XML would be too long"));
    }

    @Test
    public void shouldUseRoutedToolGateAndReviewBudgetInPrompts() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("[Intent Routing Result].allowedTools is the backend-derived per-turn tool gate"));
        assertTrue(agentPrompt.contains("Use only those tools"));
        assertTrue(agentPrompt.contains("[Intent Routing Result].maxReviewIterations is the review repair budget"));
        assertTrue(agentPrompt.contains("If it is 0, do the initial drawing action only"));
        assertTrue(agentPrompt.contains("The intent router gives high-level direction only"));
    }

    @Test
    public void shouldHardenDrawerProtocolAgainstProseOnlyOutputs() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");
        String architectureSkill = readResource("agent/skills/drawio-architecture/SKILL.md");
        String visualDesignSkill = readResource("agent/skills/drawio-visual-design/SKILL.md");

        assertTrue(agentPrompt.contains("Natural-language drawing output is invalid"));
        assertTrue(agentPrompt.contains("If you cannot make a registered tool call, your first text character must be `{`"));
        assertTrue(agentPrompt.contains("Do not output design plans, internal planning notes, status prose, or explanations"));
        assertTrue(agentPrompt.contains("Protocol Guard"));
        assertTrue(architectureSkill.contains("never output the blueprint"));
        assertTrue(visualDesignSkill.contains("never output this workflow"));
    }

    @Test
    public void shouldTreatValidationFailuresAsReviewRepairSignals() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        assertTrue(agentPrompt.contains("validation_result or mutation tool response analysis with valid=false"));
        assertTrue(agentPrompt.contains("analysis describes the new draft, not the previous canvas"));
        assertTrue(agentPrompt.contains("Parse registered tool response analysis first"));
        assertTrue(agentPrompt.contains("Reject validation_result or tool response analysis valid=false"));
        assertTrue(agentPrompt.contains("major visual issues such as overlaps, opaque labels, or crowded layout"));
    }

    @Test
    public void shouldMakeReviewerTrustDeterministicGeometryReports() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");

        // Item 3: geometry and XML structure come from the deterministic engine, not reviewer re-analysis.
        assertTrue(agentPrompt.contains("Treat the latest post-mutation CanvasAnalysis or validation_result as the ground truth for geometry and structure"));
        assertTrue(agentPrompt.contains("Do not infer overlaps, edge-node crossings, malformed XML, missing geometry, or broken source/target ids from raw XML coordinates"));
        assertTrue(agentPrompt.contains("If both an initial [Diagram Quality Report] and a post-mutation analysis exist, trust the post-mutation analysis for the draft"));
        assertFalse(agentPrompt.contains("Check edges: source/target IDs must exist. Standalone lines/arrows must use sourcePoint and targetPoint."));
        assertFalse(agentPrompt.contains("Check XML validity and flag malformed or incomplete tags."));
    }

    @Test
    public void shouldKeepSemanticContentQualityOnlyInSemanticReviewer() throws Exception {
        String agentPrompt = readResource("agent/agent-draw-io.yml");
        String semanticReviewer = section(agentPrompt, "- name: agent_semantic_reviewer", "output-key: semantic_review");
        String inlineReviewer = section(agentPrompt, "- name: agent_reviewer", "output-key: review_result");

        // Item 5: 300011 owns content correctness; 300000 should not duplicate diagram-type semantic review.
        assertTrue(semanticReviewer.contains("Diagram-type content quality belongs to this semantic reviewer"));
        assertTrue(semanticReviewer.contains("architecture subtype correctness"));
        assertTrue(semanticReviewer.contains("UML relationship semantics"));
        assertFalse(inlineReviewer.contains("Check type-specific content quality"));
        assertFalse(inlineReviewer.contains("architecture must use one clear subtype"));
        assertFalse(inlineReviewer.contains("UML class must show classes/interfaces/enums"));
        assertFalse(inlineReviewer.contains("Check structure: required core objects, missing relationships, orphaned nodes, and duplicated semantic nodes."));
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

    private String section(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        if (start < 0) {
            throw new AssertionError("Missing section start: " + startMarker);
        }
        int end = text.indexOf(endMarker, start);
        if (end < 0) {
            throw new AssertionError("Missing section end: " + endMarker);
        }
        return text.substring(start, end);
    }

}
