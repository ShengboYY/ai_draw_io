package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioSkillMcpService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioSkillToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.skills.DrawioSkillAccessContext;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DrawioSkillMcpServiceTest {

    @Test
    public void shouldExposeDrawioSkillLookupToolsToLlm() {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(serviceWithFakeCatalog())
                .build()
                .getToolCallbacks();

        Set<String> toolNames = Set.copyOf(Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .toList());

        assertEquals(Set.of(
                DrawioSkillToolNames.LIST_DRAWIO_SKILLS,
                DrawioSkillToolNames.GET_DRAWIO_SKILL), toolNames);
    }

    @Test
    public void shouldReturnSharedAndSelectableSkillBodiesOnly() {
        DrawioSkillMcpService service = serviceWithFakeCatalog();

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of(
                "drawio-xml-guide",
                "custom-flow"))) {
            DrawioSkillMcpService.GetSkillResponse shared = service.getDrawioSkill(request("drawio-xml-guide"));
            DrawioSkillMcpService.GetSkillResponse selected = service.getDrawioSkill(request("custom-flow"));
            DrawioSkillMcpService.GetSkillResponse hidden = service.getDrawioSkill(request("hidden-skill"));

            assertTrue(shared.isFound());
            assertEquals("xml body", shared.getBody());
            assertTrue(selected.isFound());
            assertEquals("flow body", selected.getBody());
            assertFalse(hidden.isFound());
            assertEquals(null, hidden.getBody());
        }
    }

    @Test
    public void shouldFailClosedWhenNoRunSkillAllowlistExists() {
        DrawioSkillMcpService service = serviceWithFakeCatalog();

        DrawioSkillMcpService.GetSkillResponse selected = service.getDrawioSkill(request("custom-flow"));

        assertFalse(selected.isFound());
        assertEquals("Skill is not allowed for this Draw.io run.", selected.getMessage());
    }

    @Test
    public void shouldRejectSelectableSkillOutsideRunAllowlist() {
        DrawioSkillMcpService service = serviceWithFakeCatalog();

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("drawio-xml-guide"))) {
            DrawioSkillMcpService.GetSkillResponse selected = service.getDrawioSkill(request("custom-flow"));

            assertFalse(selected.isFound());
            assertEquals("Skill is not allowed for this Draw.io run.", selected.getMessage());
        }
    }

    @Test
    public void shouldRejectRunAllowedSkillWhenItIsNotVisibleToCurrentUser() {
        DrawioSkillMcpService service = serviceWithFakeCatalog();

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("hidden-skill"))) {
            DrawioSkillMcpService.GetSkillResponse hidden = service.getDrawioSkill(request("hidden-skill"));

            assertFalse(hidden.isFound());
            assertEquals("Skill is not visible to the current user or is not selectable for Draw.io drawing.",
                    hidden.getMessage());
        }
    }

    @Test
    public void shouldListSelectableSkillsWithoutSharedSkills() {
        DrawioSkillMcpService service = serviceWithFakeCatalog();

        DrawioSkillMcpService.ListSkillsResponse response = service.listDrawioSkills();

        assertEquals("drawio_skill_catalog", response.getType());
        assertEquals(1, response.getSkills().size());
        assertEquals("custom-flow", response.getSkills().get(0).getName());
    }

    @Test
    public void shouldScopeSelectableSkillListWhenRunAllowlistExists() {
        DrawioSkillMcpService service = serviceWithFakeCatalog();

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("drawio-xml-guide"))) {
            DrawioSkillMcpService.ListSkillsResponse response = service.listDrawioSkills();

            assertEquals("drawio_skill_catalog", response.getType());
            assertTrue(response.getSkills().isEmpty());
        }
    }

    private DrawioSkillMcpService serviceWithFakeCatalog() {
        DrawioSkillMcpService service = new DrawioSkillMcpService();
        ReflectionTestUtils.setField(service, "skillCatalogService", new FakeSkillCatalogService());
        return service;
    }

    private DrawioSkillMcpService.GetSkillRequest request(String name) {
        DrawioSkillMcpService.GetSkillRequest request = new DrawioSkillMcpService.GetSkillRequest();
        request.setName(name);
        return request;
    }

    private static class FakeSkillCatalogService extends SkillCatalogService {
        @Override
        public List<SkillInfo> selectableSkills(String ownerId) {
            return List.of(new SkillInfo("custom-flow", "Flow skill", "flow body", "drawio-design", true));
        }

        @Override
        public Set<String> selectableSkillNames(String ownerId) {
            return Set.of("custom-flow");
        }

        @Override
        public String body(String name, String ownerId) {
            return switch (name) {
                case "drawio-xml-guide" -> "xml body";
                case "drawio-visual-design" -> "visual body";
                case "custom-flow" -> "flow body";
                default -> "";
            };
        }
    }
}
