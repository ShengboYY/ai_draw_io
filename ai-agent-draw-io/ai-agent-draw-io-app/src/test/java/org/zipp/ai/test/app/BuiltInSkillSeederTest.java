package org.zipp.ai.test.app;

import org.junit.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import org.zipp.ai.config.BuiltInSkillSeeder;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillStore;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BuiltInSkillSeederTest {

    @Test
    public void shouldUpsertBuiltInDrawioDesignSkillsIntoDb() {
        BuiltInSkillSeeder seeder = new BuiltInSkillSeeder();
        RecordingSkillStore store = new RecordingSkillStore();
        SkillCatalogService catalog = new SkillCatalogService() {
            @Override
            public List<SkillInfo> builtInSkills() {
                return List.of(
                        new SkillInfo("drawio-xml-guide", "XML guide", "body-v2", "drawio-design", false),
                        new SkillInfo("pdf", "PDF", "pdf-body", "documents", true)
                );
            }
        };

        ReflectionTestUtils.setField(seeder, "seedEnabled", true);
        ReflectionTestUtils.setField(seeder, "skillStore", store);
        ReflectionTestUtils.setField(seeder, "skillCatalogService", catalog);

        seeder.run((ApplicationArguments) null);

        assertEquals(1, store.upserted.size());
        assertEquals("drawio-xml-guide", store.upserted.get(0).name());
        assertEquals("body-v2", store.upserted.get(0).body());
        assertEquals(SkillStore.Visibility.PUBLIC, store.upserted.get(0).visibility());
        assertTrue(store.seeded.isEmpty());
    }

    private static class RecordingSkillStore implements SkillStore {
        private final List<StoredSkill> upserted = new ArrayList<>();
        private final List<StoredSkill> seeded = new ArrayList<>();

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
            upserted.add(skill);
        }

        @Override
        public void seedIfAbsent(StoredSkill skill) {
            seeded.add(skill);
        }

        @Override
        public void delete(String ownerId, String name) {
        }
    }
}
