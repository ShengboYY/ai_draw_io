package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillManagementService;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillStore;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SkillManagementServiceTest {

    @Test
    public void shouldRejectInvalidDrawioSkillBeforeSaving() {
        SkillManagementService service = serviceWithStore();

        IllegalArgumentException error = assertIllegalArgument(() -> service.save(
                "alice",
                "broken-flowchart",
                "Broken skill",
                "drawio-design",
                """
                        # Broken Flowchart

                        ## Rules
                        Missing schema metadata and section priority.
                        """,
                "PRIVATE"));

        assertTrue(error.getMessage().contains("schemaVersion must be >= 1"));
        assertTrue(error.getMessage().contains("diagramType is required"));
        assertTrue(error.getMessage().contains("SKILL.md H2 section must end with [P0] or [P1]"));
    }

    @Test
    public void shouldSaveValidDrawioSkill() {
        CapturingSkillStore store = new CapturingSkillStore();
        SkillManagementService service = new SkillManagementService();
        ReflectionTestUtils.setField(service, "skillStore", store);
        ReflectionTestUtils.setField(service, "skillCatalogService", new SkillCatalogService());

        service.save(
                "alice",
                "custom-flowchart",
                "Custom flowchart skill",
                "drawio-design",
                """
                        ---
                        schemaVersion: 1
                        diagramType: flowchart
                        ---

                        # Custom Flowchart

                        ## Rules [P0]
                        Use these flowchart rules.
                        """,
                "PRIVATE");

        assertEquals("custom-flowchart", store.saved.name());
    }

    private SkillManagementService serviceWithStore() {
        SkillManagementService service = new SkillManagementService();
        ReflectionTestUtils.setField(service, "skillStore", new CapturingSkillStore());
        ReflectionTestUtils.setField(service, "skillCatalogService", new SkillCatalogService());
        return service;
    }

    private IllegalArgumentException assertIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return expected;
        }
        throw new AssertionError("expected IllegalArgumentException");
    }

    private static class CapturingSkillStore implements SkillStore {
        private StoredSkill saved;

        @Override
        public List<StoredSkill> listPublic() {
            return List.of();
        }

        @Override
        public List<StoredSkill> listByOwner(String ownerId) {
            return List.of();
        }

        @Override
        public void upsert(StoredSkill skill) {
            saved = skill;
        }

        @Override
        public void seedIfAbsent(StoredSkill skill) {
        }

        @Override
        public void delete(String ownerId, String name) {
        }
    }
}
