package org.zipp.ai.trigger.http.service;

import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * Builds the skill-rules section injected into the routed message.
 *
 * <p>The model never calls the registered SkillsTool in the streaming path, so skill bodies never
 * reach it. The intent router selects a skill (skillName) from the dynamic {@link SkillCatalogService}
 * catalog; this loads that skill's body (plus the shared visual-design rules) and feeds it to the
 * drawing agent directly. Skills are discovered at runtime, so user-added skills work without code changes.
 */
@Service
public class SkillContentProvider {

    @Resource
    private SkillCatalogService skillCatalogService;

    /**
     * Build the skill-rules section to prepend before the user request, for a given routed skillName.
     * Returns an empty string when there is nothing useful to inject.
     */
    public String buildSkillSection(String skillName) {
        StringBuilder section = new StringBuilder();

        String selected = StringUtils.trimToNull(skillName);
        if (selected != null
                && !"none".equalsIgnoreCase(selected)
                && !SkillCatalogService.SHARED_SKILL.equals(selected)
                && skillCatalogService.exists(selected)) {
            appendSkill(section, selected);
        }
        // The drawing agent is instructed to always follow the shared visual-design rules.
        appendSkill(section, SkillCatalogService.SHARED_SKILL);

        return section.toString();
    }

    private void appendSkill(StringBuilder section, String skillName) {
        String body = skillCatalogService.body(skillName);
        if (StringUtils.isNotBlank(body)) {
            section.append("[Skill Rules: ").append(skillName).append("]\n")
                    .append(body.trim()).append("\n\n");
        }
    }
}
