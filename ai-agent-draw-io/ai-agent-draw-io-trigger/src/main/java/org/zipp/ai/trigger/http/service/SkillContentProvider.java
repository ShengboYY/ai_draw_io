package org.zipp.ai.trigger.http.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Loads the relevant skill's SKILL.md from the classpath and injects it into the routed message.
 *
 * <p>The model never calls the registered SkillsTool in the streaming path, so skill bodies never
 * reach it. The intent router already decides which skill applies (skillName), so the backend loads
 * that one skill (plus the shared visual-design rules) and feeds it to the drawing agent directly.
 * This keeps skill content targeted to the request instead of dumping all skills every time.
 */
@Slf4j
@Service
public class SkillContentProvider {

    private static final String SHARED_SKILL = "drawio-visual-design";

    // Diagram skills the intent router may select; guards against loading arbitrary paths.
    private static final Set<String> KNOWN_SKILLS = Set.of(
            "drawio-uml", "drawio-flowchart", "drawio-architecture", "drawio-sequence",
            "drawio-er", "drawio-usecase", "drawio-state", "drawio-visual-design");

    private final ConcurrentMap<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Build the skill-rules section to prepend before the user request, for a given routed skillName.
     * Returns an empty string when there is nothing useful to inject.
     */
    public String buildSkillSection(String skillName) {
        StringBuilder section = new StringBuilder();

        String selected = normalize(skillName);
        if (selected != null && !SHARED_SKILL.equals(selected)) {
            appendSkill(section, selected);
        }
        // The drawing agent is instructed to always follow the shared visual-design rules.
        appendSkill(section, SHARED_SKILL);

        return section.toString();
    }

    private void appendSkill(StringBuilder section, String skillName) {
        String content = load(skillName);
        if (StringUtils.isNotBlank(content)) {
            section.append("[Skill Rules: ").append(skillName).append("]\n")
                    .append(content.trim()).append("\n\n");
        }
    }

    private String normalize(String skillName) {
        if (StringUtils.isBlank(skillName) || "none".equalsIgnoreCase(skillName)) {
            return null;
        }
        String trimmed = skillName.trim();
        return KNOWN_SKILLS.contains(trimmed) ? trimmed : null;
    }

    private String load(String skillName) {
        return cache.computeIfAbsent(skillName, name -> {
            try {
                ClassPathResource resource = new ClassPathResource("agent/skills/" + name + "/SKILL.md");
                if (!resource.exists()) {
                    log.warn("Skill file not found for {}", name);
                    return "";
                }
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("Failed to load skill content for {}: {}", name, e.toString());
                return "";
            }
        });
    }
}
