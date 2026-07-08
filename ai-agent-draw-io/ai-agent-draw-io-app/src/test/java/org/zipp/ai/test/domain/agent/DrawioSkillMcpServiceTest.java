package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioSkillMcpService;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioSkillToolNames;
import org.zipp.ai.domain.agent.service.armory.matter.skills.DrawioSkillAccessContext;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillToolTraceContext;

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
                DrawioSkillToolNames.GET_DRAWIO_SKILL,
                DrawioSkillToolNames.GET_DRAWIO_SKILL_SECTION), toolNames);
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
            assertWrappedBody(shared, "drawio-xml-guide", "xml body");
            assertTrue(selected.isFound());
            assertWrappedBody(selected, "custom-flow", "flow body");
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
            assertEquals("not_allowed", selected.getFailureCode());
            assertEquals("abort", selected.getFallbackAction());
            assertFalse(selected.getDegraded());
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
            assertEquals("not_visible", hidden.getFailureCode());
            assertEquals("continue_with_shared_skills", hidden.getFallbackAction());
            assertTrue(hidden.getDegraded());
        }
    }

    @Test
    public void shouldAllowDiagramSkillDegradationWhenBodyIsMissing() {
        DrawioSkillMcpService service = serviceWithMissingBodyCatalog("empty-skill");

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("empty-skill"))) {
            DrawioSkillMcpService.GetSkillResponse response = service.getDrawioSkill(request("empty-skill"));

            assertFalse(response.isFound());
            assertEquals("body_missing", response.getFailureCode());
            assertEquals("continue_with_shared_skills", response.getFallbackAction());
            assertTrue(response.getDegraded());
        }
    }

    @Test
    public void shouldAbortWhenSharedSkillBodyIsMissing() {
        DrawioSkillMcpService service = serviceWithMissingBodyCatalog("drawio-xml-guide");

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("drawio-xml-guide"))) {
            DrawioSkillMcpService.GetSkillResponse response = service.getDrawioSkill(request("drawio-xml-guide"));

            assertFalse(response.isFound());
            assertEquals("body_missing", response.getFailureCode());
            assertEquals("abort", response.getFallbackAction());
            assertFalse(response.getDegraded());
        }
    }

    @Test
    public void shouldFailClosedSkillListWhenNoRunAllowlistExists() {
        DrawioSkillMcpService service = serviceWithFakeCatalog();

        DrawioSkillMcpService.ListSkillsResponse response = service.listDrawioSkills();

        assertEquals("drawio_skill_catalog", response.getType());
        assertTrue(response.getSkills().isEmpty());
    }

    @Test
    public void shouldListAllowedSelectableSkillsWithoutSharedSkills() {
        DrawioSkillMcpService service = serviceWithFakeCatalog();

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of(
                "drawio-xml-guide",
                "custom-flow"))) {
            DrawioSkillMcpService.ListSkillsResponse response = service.listDrawioSkills();

            assertEquals("drawio_skill_catalog", response.getType());
            assertEquals(1, response.getSkills().size());
            assertEquals("custom-flow", response.getSkills().get(0).getName());
        }
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

    @Test
    public void shouldCapReturnedSkillBodiesAtFifteenThousandChars() {
        DrawioSkillMcpService service = serviceWithLongSkillCatalog();

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("long-skill"))) {
            DrawioSkillMcpService.GetSkillResponse response = service.getDrawioSkill(request("long-skill"));

            assertTrue(response.isFound());
            assertTrue(response.getTruncated());
            assertEquals(Integer.valueOf(15_001), response.getFullBodyChars());
            assertEquals(Integer.valueOf(15_000), response.getReturnedBodyChars());
            assertEquals("plain_truncated", response.getAssemblyMode());
            assertEquals(Integer.valueOf(0), response.getSectionCount());
            assertEquals(Integer.valueOf(0), response.getTruncatedSectionCount());
            assertTrue(response.getBody().contains("[Skill Rules: long-skill]"));
            assertTrue(response.getBody().contains("\n...[truncated]\n[End Skill Rules]"));
        }
    }

    @Test
    public void shouldReturnStructuredSectionMetadata() {
        DrawioSkillMcpService service = serviceWithSingleSkill("structured-skill", """
                # Structured Skill

                ## Rules [P0]
                Must keep rules.

                ## Checklist [P1]
                Check before output.
                """);

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("structured-skill"))) {
            DrawioSkillMcpService.GetSkillResponse response = service.getDrawioSkill(request("structured-skill"));

            assertTrue(response.isFound());
            assertFalse(response.getTruncated());
            assertEquals("section_full", response.getAssemblyMode());
            assertEquals(Integer.valueOf(2), response.getSectionCount());
            assertEquals(Integer.valueOf(0), response.getTruncatedSectionCount());
            assertEquals(2, response.getSections().size());
            assertEquals("rules", response.getSections().get(0).getId());
            assertEquals("P0", response.getSections().get(0).getPriority());
            assertEquals("checklist", response.getSections().get(1).getId());
            assertEquals("P1", response.getSections().get(1).getPriority());
            assertTrue(response.getOmittedSections().isEmpty());
        }
    }

    @Test
    public void shouldReturnOneSkillSectionFromSkillBody() {
        DrawioSkillMcpService service = serviceWithSingleSkill("sectioned-skill", """
                # Sectioned Skill

                ## Rules [P0]
                Keep only these rules.

                ## Checklist [P1]
                Check before output.
                """);

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("sectioned-skill"))) {
            DrawioSkillMcpService.GetSkillSectionResponse response = service.getDrawioSkillSection(
                    sectionRequest("sectioned-skill", "rules", "skill"));

            assertTrue(response.isFound());
            assertEquals("drawio_skill_section", response.getType());
            assertEquals("sectioned-skill", response.getName());
            assertEquals("rules", response.getSectionId());
            assertEquals("skill", response.getSource());
            assertEquals("P0", response.getPriority());
            assertEquals("Rules", response.getTitle());
            assertTrue(response.getBody().contains("[Skill Section: sectioned-skill#rules source=skill]"));
            assertTrue(response.getBody().contains("Keep only these rules."));
            assertFalse(response.getBody().contains("Check before output."));
        }
    }

    @Test
    public void shouldReturnOneSkillSectionFromReferenceBody() {
        DrawioSkillMcpService service = serviceWithSingleSkill("reference-skill",
                """
                        # Reference Skill

                        ## Golden Example [P0]
                        Call get_drawio_skill_section with source=reference for the exact XML.
                        """,
                """
                        # Reference Skill Details

                        ## Golden Example [P0]
                        Exact XML lives here.
                        """);

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("reference-skill"))) {
            DrawioSkillMcpService.GetSkillSectionResponse response = service.getDrawioSkillSection(
                    sectionRequest("reference-skill", "golden-example", "reference"));

            assertTrue(response.isFound());
            assertEquals("reference", response.getSource());
            assertTrue(response.getBody().contains("Exact XML lives here."));
            assertFalse(response.getBody().contains("Call get_drawio_skill_section"));
        }
    }

    @Test
    public void shouldFailClosedForDisallowedSkillSection() {
        DrawioSkillMcpService service = serviceWithSingleSkill("sectioned-skill", """
                # Sectioned Skill

                ## Rules [P0]
                Keep only these rules.
                """);

        DrawioSkillMcpService.GetSkillSectionResponse response = service.getDrawioSkillSection(
                sectionRequest("sectioned-skill", "rules", "skill"));

        assertFalse(response.isFound());
        assertEquals("Skill is not allowed for this Draw.io run.", response.getMessage());
        assertEquals("not_allowed", response.getFailureCode());
        assertEquals("abort", response.getFallbackAction());
        assertFalse(response.getDegraded());
        assertEquals(null, response.getBody());
    }

    @Test
    public void shouldAllowReferenceSectionDegradationWhenSectionIsMissing() {
        DrawioSkillMcpService service = serviceWithSingleSkill("sectioned-skill", """
                # Sectioned Skill

                ## Rules [P0]
                Keep only these rules.
                """);

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("sectioned-skill"))) {
            DrawioSkillMcpService.GetSkillSectionResponse response = service.getDrawioSkillSection(
                    sectionRequest("sectioned-skill", "golden-example", "reference"));

            assertFalse(response.isFound());
            assertEquals("section_not_found", response.getFailureCode());
            assertEquals("continue_without_reference_section", response.getFallbackAction());
            assertTrue(response.getDegraded());
            assertEquals(null, response.getBody());
        }
    }

    @Test
    public void shouldAttachTraceFieldsToSkillLookups() {
        DrawioSkillMcpService service = serviceWithSingleSkill("traced-skill", """
                # Traced Skill

                ## Rules [P0]
                Trace this call.
                """);

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("traced-skill"));
             SkillToolTraceContext.Scope trace = SkillToolTraceContext.bind("aru_test", "ses_test", "call_test")) {
            DrawioSkillMcpService.GetSkillResponse body = service.getDrawioSkill(request("traced-skill"));
            DrawioSkillMcpService.GetSkillSectionResponse section = service.getDrawioSkillSection(
                    sectionRequest("traced-skill", "rules", "skill"));

            assertEquals("aru_test", body.getTraceId());
            assertEquals("ses_test", body.getSessionId());
            assertEquals("call_test", body.getInvocationId());
            assertEquals("aru_test", section.getTraceId());
            assertEquals("ses_test", section.getSessionId());
            assertEquals("call_test", section.getInvocationId());
        }
    }

    @Test
    public void shouldPreferP0SectionsWhenStructuredBodyExceedsBudget() {
        DrawioSkillMcpService service = serviceWithSingleSkill("budgeted-skill", """
                # Budgeted Skill

                ## Rules [P0]
                Must keep rules.

                ## House Patterns [P1]
                %s

                ## Golden Example [P0]
                Must keep golden anchors.
                """.formatted("x".repeat(16_000)));

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("budgeted-skill"))) {
            DrawioSkillMcpService.GetSkillResponse response = service.getDrawioSkill(request("budgeted-skill"));

            assertTrue(response.isFound());
            assertTrue(response.getTruncated());
            assertEquals("section_budgeted", response.getAssemblyMode());
            assertEquals(Integer.valueOf(3), response.getSectionCount());
            assertEquals(Integer.valueOf(0), response.getTruncatedSectionCount());
            assertTrue(response.getBody().contains("Must keep rules."));
            assertTrue(response.getBody().contains("Must keep golden anchors."));
            assertFalse(response.getBody().contains("x".repeat(1_000)));
            assertEquals(1, response.getOmittedSections().size());
            assertEquals("house-patterns", response.getOmittedSections().get(0).getId());
        }
    }

    @Test
    public void shouldPreferP0SectionsOverLongIntroWhenStructuredBodyExceedsBudget() {
        DrawioSkillMcpService service = serviceWithSingleSkill("intro-heavy-skill", """
                # Intro Heavy Skill
                %s

                ## Rules [P0]
                Must keep the priority rules.
                """.formatted("intro ".repeat(3_000)));

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("intro-heavy-skill"))) {
            DrawioSkillMcpService.GetSkillResponse response = service.getDrawioSkill(request("intro-heavy-skill"));

            assertTrue(response.isFound());
            assertTrue(response.getTruncated());
            assertTrue(response.getBody().contains("Must keep the priority rules."));
            assertEquals(1, response.getSections().size());
            assertEquals("rules", response.getSections().get(0).getId());
        }
    }

    @Test
    public void shouldNotReturnHalfOpenCodeFenceWhenTruncatingPrioritySection() {
        DrawioSkillMcpService service = serviceWithSingleSkill("fenced-skill", """
                # Fenced Skill

                ## Golden Example [P0]
                Keep the explanation before XML.

                ```xml
                %s
                ```
                """.formatted("<mxCell id=\"2\" />\n".repeat(1_200)));

        try (DrawioSkillAccessContext.Scope ignored = DrawioSkillAccessContext.bind(Set.of("fenced-skill"))) {
            DrawioSkillMcpService.GetSkillResponse response = service.getDrawioSkill(request("fenced-skill"));

            assertTrue(response.isFound());
            assertTrue(response.getTruncated());
            assertEquals("section_budgeted", response.getAssemblyMode());
            assertEquals(Integer.valueOf(1), response.getSectionCount());
            assertEquals(Integer.valueOf(1), response.getTruncatedSectionCount());
            assertTrue(response.getBody().contains("Keep the explanation before XML."));
            assertTrue(response.getBody().contains("...[section truncated]"));
            assertEquals(0, count(response.getBody(), "```") % 2);
        }
    }

    private void assertWrappedBody(DrawioSkillMcpService.GetSkillResponse response, String skillName, String rawBody) {
        assertTrue(response.getBody().contains("[Skill Rules: " + skillName + "]"));
        assertTrue(response.getBody().contains("reference guidance only"));
        assertTrue(response.getBody().contains(rawBody));
        assertTrue(response.getBody().contains("[End Skill Rules]"));
    }

    private DrawioSkillMcpService serviceWithFakeCatalog() {
        DrawioSkillMcpService service = new DrawioSkillMcpService();
        ReflectionTestUtils.setField(service, "skillCatalogService", new FakeSkillCatalogService());
        return service;
    }

    private DrawioSkillMcpService serviceWithLongSkillCatalog() {
        DrawioSkillMcpService service = new DrawioSkillMcpService();
        ReflectionTestUtils.setField(service, "skillCatalogService", new LongSkillCatalogService());
        return service;
    }

    private DrawioSkillMcpService serviceWithMissingBodyCatalog(String name) {
        DrawioSkillMcpService service = new DrawioSkillMcpService();
        ReflectionTestUtils.setField(service, "skillCatalogService", new MissingBodySkillCatalogService(name));
        return service;
    }

    private DrawioSkillMcpService serviceWithSingleSkill(String name, String body) {
        DrawioSkillMcpService service = new DrawioSkillMcpService();
        ReflectionTestUtils.setField(service, "skillCatalogService", new SingleSkillCatalogService(name, body, ""));
        return service;
    }

    private DrawioSkillMcpService serviceWithSingleSkill(String name, String body, String referenceBody) {
        DrawioSkillMcpService service = new DrawioSkillMcpService();
        ReflectionTestUtils.setField(service, "skillCatalogService",
                new SingleSkillCatalogService(name, body, referenceBody));
        return service;
    }

    private DrawioSkillMcpService.GetSkillRequest request(String name) {
        DrawioSkillMcpService.GetSkillRequest request = new DrawioSkillMcpService.GetSkillRequest();
        request.setName(name);
        return request;
    }

    private DrawioSkillMcpService.GetSkillSectionRequest sectionRequest(String name, String sectionId, String source) {
        DrawioSkillMcpService.GetSkillSectionRequest request = new DrawioSkillMcpService.GetSkillSectionRequest();
        request.setName(name);
        request.setSectionId(sectionId);
        request.setSource(source);
        return request;
    }

    private int count(String value, String needle) {
        int count = 0;
        int index = value.indexOf(needle);
        while (index >= 0) {
            count++;
            index = value.indexOf(needle, index + needle.length());
        }
        return count;
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

    private static class LongSkillCatalogService extends SkillCatalogService {
        @Override
        public List<SkillInfo> selectableSkills(String ownerId) {
            return List.of(new SkillInfo("long-skill", "Long skill", longBody(), "drawio-design", true));
        }

        @Override
        public Set<String> selectableSkillNames(String ownerId) {
            return Set.of("long-skill");
        }

        @Override
        public String body(String name, String ownerId) {
            return "long-skill".equals(name) ? longBody() : "";
        }

        private String longBody() {
            return "a".repeat(15_001);
        }
    }

    private static class MissingBodySkillCatalogService extends SkillCatalogService {
        private final String name;

        private MissingBodySkillCatalogService(String name) {
            this.name = name;
        }

        @Override
        public List<SkillInfo> selectableSkills(String ownerId) {
            return List.of(new SkillInfo(name, "Missing body skill", "", "drawio-design", true));
        }

        @Override
        public Set<String> selectableSkillNames(String ownerId) {
            return Set.of(name);
        }

        @Override
        public String body(String name, String ownerId) {
            return "";
        }
    }

    private static class SingleSkillCatalogService extends SkillCatalogService {
        private final String name;
        private final String body;
        private final String referenceBody;

        private SingleSkillCatalogService(String name, String body, String referenceBody) {
            this.name = name;
            this.body = body;
            this.referenceBody = referenceBody;
        }

        @Override
        public List<SkillInfo> selectableSkills(String ownerId) {
            return List.of(new SkillInfo(name, "Single skill", body, "drawio-design", true));
        }

        @Override
        public Set<String> selectableSkillNames(String ownerId) {
            return Set.of(name);
        }

        @Override
        public String body(String name, String ownerId) {
            return this.name.equals(name) ? body : "";
        }

        @Override
        public String referenceBody(String name, String ownerId) {
            return this.name.equals(name) ? referenceBody : "";
        }
    }
}
