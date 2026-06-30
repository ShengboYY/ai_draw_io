package org.zipp.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillStore;

import javax.annotation.Resource;

/**
 * On startup, seeds the built-in (jar-bundled) draw.io skills into the DB as PUBLIC if absent.
 * Non-destructive (never overwrites edited/evolved DB copies), so every environment has the platform
 * skills available without a manual SQL step. No-op when the DB store is not configured.
 */
@Slf4j
@Component
public class BuiltInSkillSeeder implements ApplicationRunner {

    private static final String DESIGN_CATEGORY = "drawio-design";

    @Value("${SEED_BUILTIN_SKILLS:true}")
    private boolean seedEnabled;

    @Autowired(required = false)
    private SkillStore skillStore;

    @Resource
    private SkillCatalogService skillCatalogService;

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled || skillStore == null) {
            return;
        }
        int seeded = 0;
        for (SkillCatalogService.SkillInfo skill : skillCatalogService.builtInSkills()) {
            if (!DESIGN_CATEGORY.equals(skill.category())) {
                continue;
            }
            try {
                skillStore.seedIfAbsent(new SkillStore.StoredSkill(
                        "", skill.name(), skill.description(), skill.category(),
                        skill.body(), SkillStore.Visibility.PUBLIC, true));
                seeded++;
            } catch (Exception e) {
                log.warn("Seeding built-in skill {} failed: {}", skill.name(), e.toString());
            }
        }
        log.info("Built-in skill seeding done: ensured {} drawio-design skills as PUBLIC", seeded);
    }
}
