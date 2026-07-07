package org.zipp.ai.trigger.http.service;

import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillCatalogService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the skill-tool section injected into the routed message.
 *
 * <p>The router or user chooses skill names from the dynamic {@link SkillCatalogService} catalog.
 * The drawing agent then calls the registered Draw.io skill tools to load those bodies explicitly,
 * so skill retrieval is a standard tool call instead of hidden prompt stuffing.
 */
@Service
public class SkillContentProvider {

    @Resource
    private SkillCatalogService skillCatalogService;

    /**
     * Build the required skill lookup section for one or more chosen skills. Each selected skill is
     * included only if it is visible to the user; shared XML and visual rules are always required.
     */
    public String buildSkillSection(List<String> skillNames, String ownerId) {
        return buildSkillSectionWithMetadata(skillNames, ownerId).text();
    }

    public SkillSection buildSkillSectionWithMetadata(List<String> skillNames, String ownerId) {
        Set<String> required = new LinkedHashSet<>();
        required.add(SkillCatalogService.SHARED_XML_GUIDE_SKILL);
        required.add(SkillCatalogService.SHARED_SKILL);

        if (skillNames != null) {
            // Only skills actually selectable for this user may be listed. Fetched once, and it must
            // be the selectable whitelist (not exists()/full catalog) so a user-supplied name cannot
            // force a hidden / non-drawio / shared skill into the drawer's required lookup list.
            Set<String> selectable = skillCatalogService.selectableSkillNames(ownerId);
            for (String skillName : skillNames) {
                String selected = StringUtils.trimToNull(skillName);
                if (selected != null
                        && !"none".equalsIgnoreCase(selected)
                        && !SkillCatalogService.SHARED_SKILL.equals(selected)
                        && !SkillCatalogService.SHARED_XML_GUIDE_SKILL.equals(selected)
                        && selectable.contains(selected)) {
                    required.add(selected);
                }
            }
        }

        StringBuilder section = new StringBuilder();
        section.append("[Required Skill Tool Calls]\n")
                .append("Skill rules are not embedded in this prompt. Before the first canvas-mutating ")
                .append("tool call, call get_drawio_skill once for each required skill below and follow ")
                .append("the returned bodies as reference drawing guidance only.\n");
        for (String skillName : required) {
            section.append("- ").append(skillName).append('\n');
        }
        section.append("[End Required Skill Tool Calls]\n\n");
        return new SkillSection(section.toString(), Collections.unmodifiableSet(new LinkedHashSet<>(required)));
    }

    public record SkillSection(String text, Set<String> requiredSkillNames) {
        public static SkillSection empty() {
            return new SkillSection("", Set.of());
        }
    }
}
