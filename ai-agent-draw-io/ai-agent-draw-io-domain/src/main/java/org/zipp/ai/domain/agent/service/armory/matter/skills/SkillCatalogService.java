package org.zipp.ai.domain.agent.service.armory.matter.skills;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runtime registry of skills, discovered by scanning agent/skills/*\/SKILL.md on the classpath.
 *
 * <p>Skills are not hardcoded: dropping a new folder with a SKILL.md (name + description frontmatter)
 * makes it appear here automatically, which is what lets users add (and evolve) their own skills.
 * Selection downstream is driven by the {@code description}, not a fixed enum.
 */
@Slf4j
@Service
public class SkillCatalogService {

    /** Always-applied shared design rules; excluded from the selectable catalog. */
    public static final String SHARED_SKILL = "drawio-visual-design";

    private static final String SKILLS_LOCATION = "classpath*:agent/skills/*/SKILL.md";
    private static final Pattern FRONTMATTER = Pattern.compile("^\\s*---\\s*\\n(.*?)\\n---\\s*\\n?", Pattern.DOTALL);
    private static final Pattern FOLDER = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$");

    public record SkillInfo(String name, String description, String body) {
    }

    /** name -> SkillInfo, discovery order preserved. Loaded once, lazily. */
    private volatile Map<String, SkillInfo> catalog;

    private Map<String, SkillInfo> catalog() {
        Map<String, SkillInfo> local = catalog;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (catalog == null) {
                catalog = scan();
            }
            return catalog;
        }
    }

    private Map<String, SkillInfo> scan() {
        Map<String, SkillInfo> discovered = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(SKILLS_LOCATION);
            for (Resource resource : resources) {
                try {
                    String raw = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                    String folder = folderName(resource);
                    SkillInfo info = parse(raw, folder);
                    if (info != null) {
                        discovered.put(info.name(), info);
                    }
                } catch (Exception e) {
                    log.warn("Skipping unreadable skill resource {}: {}", resource, e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Skill scan failed: {}", e.toString());
        }
        log.info("Skill catalog loaded: {} skills {}", discovered.size(), discovered.keySet());
        return discovered;
    }

    private SkillInfo parse(String raw, String folder) {
        String name = folder;
        String description = "";
        String body = raw;

        Matcher m = FRONTMATTER.matcher(raw);
        if (m.find()) {
            String front = m.group(1);
            body = raw.substring(m.end());
            String fmName = field(front, "name");
            if (fmName != null && !fmName.isBlank()) {
                name = fmName.trim();
            }
            String fmDesc = field(front, "description");
            if (fmDesc != null) {
                description = fmDesc.trim();
            }
        }

        if (name == null || name.isBlank()) {
            return null;
        }
        return new SkillInfo(name, description, body.trim());
    }

    private String field(String frontmatter, String key) {
        for (String line : frontmatter.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + ":")) {
                return trimmed.substring((key + ":").length()).trim();
            }
        }
        return null;
    }

    private String folderName(Resource resource) {
        try {
            Matcher m = FOLDER.matcher(resource.getURL().toString());
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Skills the router may select (everything discovered except the always-applied shared skill). */
    public List<SkillInfo> selectableSkills() {
        List<SkillInfo> list = new ArrayList<>();
        for (SkillInfo info : catalog().values()) {
            if (!SHARED_SKILL.equals(info.name())) {
                list.add(info);
            }
        }
        return list;
    }

    /** A compact menu (name + description) for prompting a selector / router. */
    public String catalogText() {
        StringBuilder sb = new StringBuilder();
        for (SkillInfo info : selectableSkills()) {
            sb.append("- ").append(info.name()).append(": ").append(info.description()).append('\n');
        }
        return sb.toString();
    }

    public boolean exists(String name) {
        return name != null && catalog().containsKey(name.trim());
    }

    /** SKILL.md body (frontmatter stripped) for a discovered skill, or empty if unknown. */
    public String body(String name) {
        if (name == null) {
            return "";
        }
        SkillInfo info = catalog().get(name.trim());
        return info == null ? "" : info.body();
    }
}
