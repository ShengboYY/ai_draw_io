package org.zipp.ai.domain.agent.service.armory.matter.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runtime registry of skills, merged per request from (in increasing precedence):
 * <ol>
 *   <li>built-in: {@code agent/skills/*\/SKILL.md} on the classpath (bundled in the jar);</li>
 *   <li>external dir: {@code <DRAWIO_SKILLS_DIR>/<skill>/SKILL.md} (optional dev/ops files);</li>
 *   <li>public DB skills: platform-provided, available to everyone;</li>
 *   <li>private DB skills: the requesting user's own skills.</li>
 * </ol>
 *
 * <p>The shared base (built-in + external + public) is rebuilt on a short throttle so new/edited
 * skills appear without a restart; per-user private skills are overlaid per request. The DB store is
 * optional — if it is unavailable the catalog gracefully degrades to built-in + external skills.
 */
@Slf4j
@Service
public class SkillCatalogService {

    /** Always-applied shared design rules; excluded from the selectable catalog. */
    public static final String SHARED_SKILL = "drawio-visual-design";
    public static final String SHARED_XML_GUIDE_SKILL = "drawio-xml-guide";

    private static final String ROUTER_SKILL_CATEGORY = "drawio-design";
    private static final String CLASSPATH_LOCATION = "classpath*:agent/skills/*/SKILL.md";
    private static final Pattern FRONTMATTER = Pattern.compile("^\\s*---\\s*\\n(.*?)\\n---\\s*\\n?", Pattern.DOTALL);
    private static final Pattern FOLDER = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$");
    private static final long REFRESH_THROTTLE_MS = 2000;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Optional writable external skills dir; empty = built-in + DB only. */
    @Value("${DRAWIO_SKILLS_DIR:}")
    private String externalSkillsDir;

    /** Optional DB-backed store for public + per-user skills; null = built-in + external only. */
    @Autowired(required = false)
    private SkillStore skillStore;

    public record SkillInfo(String name, String description, String body, String category, boolean selectable) {
    }

    private volatile Map<String, SkillInfo> classpathSkills; // immutable at runtime, scanned once
    private volatile Map<String, SkillInfo> base;            // built-in + external + public, throttled refresh
    private volatile long lastBaseAt = 0L;

    /** Shared catalog (built-in + external + public DB), rebuilt at most every {@link #REFRESH_THROTTLE_MS}. */
    private Map<String, SkillInfo> base() {
        long now = System.currentTimeMillis();
        if (base != null && now - lastBaseAt < REFRESH_THROTTLE_MS) {
            return base;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (base != null && now - lastBaseAt < REFRESH_THROTTLE_MS) {
                return base;
            }
            lastBaseAt = now;
            Map<String, SkillInfo> merged = new LinkedHashMap<>(classpathSkills());
            merged.putAll(scanExternal());
            merged.putAll(publicDbSkills());
            base = merged;
            log.info("Skill catalog base loaded: {} skills (built-in {}) {}",
                    merged.size(), classpathSkills().size(), merged.keySet());
            return base;
        }
    }

    /** Full catalog for a given user: shared base plus that user's private skills (highest precedence). */
    private Map<String, SkillInfo> catalog(String ownerId) {
        Map<String, SkillInfo> merged = new LinkedHashMap<>(base());
        if (skillStore != null && ownerId != null && !ownerId.isBlank()) {
            try {
                for (SkillStore.StoredSkill skill : skillStore.listByOwner(ownerId)) {
                    SkillInfo info = toInfo(skill);
                    if (info != null) {
                        merged.put(info.name(), info);
                    }
                }
            } catch (Exception e) {
                log.warn("Loading private skills for owner {} failed: {}", ownerId, e.toString());
            }
        }
        return merged;
    }

    private Map<String, SkillInfo> publicDbSkills() {
        Map<String, SkillInfo> result = new LinkedHashMap<>();
        if (skillStore == null) {
            return result;
        }
        try {
            for (SkillStore.StoredSkill skill : skillStore.listPublic()) {
                SkillInfo info = toInfo(skill);
                if (info != null) {
                    result.put(info.name(), info);
                }
            }
        } catch (Exception e) {
            log.warn("Loading public skills failed, degrading to built-in/external: {}", e.toString());
        }
        return result;
    }

    private SkillInfo toInfo(SkillStore.StoredSkill skill) {
        if (skill == null || skill.name() == null || skill.name().isBlank()) {
            return null;
        }
        String category = skill.category() == null ? "" : skill.category().trim();
        return new SkillInfo(skill.name().trim(),
                skill.description() == null ? "" : skill.description().trim(),
                skill.body() == null ? "" : skill.body().trim(),
                category, skill.enabled());
    }

    private Map<String, SkillInfo> classpathSkills() {
        Map<String, SkillInfo> local = classpathSkills;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (classpathSkills == null) {
                classpathSkills = scanClasspath();
            }
            return classpathSkills;
        }
    }

    private Map<String, SkillInfo> scanClasspath() {
        Map<String, SkillInfo> discovered = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(CLASSPATH_LOCATION);
            for (Resource resource : resources) {
                try {
                    String raw = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                    SkillInfo info = parse(raw, folderName(resource.getURL().toString()));
                    if (info != null) {
                        discovered.put(info.name(), info);
                    }
                } catch (Exception e) {
                    log.warn("Skipping unreadable built-in skill {}: {}", resource, e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("Built-in skill scan failed: {}", e.toString());
        }
        return discovered;
    }

    private Map<String, SkillInfo> scanExternal() {
        Map<String, SkillInfo> discovered = new LinkedHashMap<>();
        if (!hasExternalDir()) {
            return discovered;
        }
        Path root = Path.of(externalSkillsDir.trim());
        if (!Files.isDirectory(root)) {
            return discovered;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                Path md = dir.resolve("SKILL.md");
                if (Files.isRegularFile(md)) {
                    try {
                        String raw = Files.readString(md, StandardCharsets.UTF_8);
                        SkillInfo info = parse(raw, dir.getFileName().toString());
                        if (info != null) {
                            discovered.put(info.name(), info);
                        }
                    } catch (Exception e) {
                        log.warn("Skipping unreadable external skill {}: {}", md, e.toString());
                    }
                }
            });
        } catch (IOException e) {
            log.warn("External skill scan failed for {}: {}", externalSkillsDir, e.toString());
        }
        return discovered;
    }

    private boolean hasExternalDir() {
        return externalSkillsDir != null && !externalSkillsDir.isBlank();
    }

    private SkillInfo parse(String raw, String folder) {
        String name = folder;
        String description = "";
        String body = raw;
        String category = "";
        boolean selectable = true;

        Matcher m = FRONTMATTER.matcher(raw);
        if (m.find()) {
            String front = m.group(1);
            body = raw.substring(m.end());
            Map<String, Object> metadata = frontmatter(front);
            String fmName = stringValue(metadata.get("name"));
            if (fmName != null && !fmName.isBlank()) {
                name = fmName.trim();
            }
            String fmDesc = stringValue(metadata.get("description"));
            if (fmDesc != null) {
                description = fmDesc.trim();
            }
            Map<String, Object> nestedMetadata = mapValue(metadata.get("metadata"));
            category = stringValue(nestedMetadata.get("category"));
            selectable = booleanValue(nestedMetadata.get("selectable"), true);
        }

        if (name == null || name.isBlank()) {
            return null;
        }
        return new SkillInfo(name, description, body.trim(), category == null ? "" : category.trim(), selectable);
    }

    private Map<String, Object> frontmatter(String frontmatter) {
        try {
            Map<String, Object> parsed = YAML.readValue(frontmatter, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("Unable to parse skill frontmatter as YAML: {}", e.toString());
            return Map.of();
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return Map.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String folderName(String url) {
        Matcher m = FOLDER.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Built-in skills bundled in the jar (classpath), used to seed the DB. */
    public List<SkillInfo> builtInSkills() {
        return new ArrayList<>(classpathSkills().values());
    }

    /** Skills the given user's router may select: draw.io design skills, excluding shared skills. */
    public List<SkillInfo> selectableSkills(String ownerId) {
        List<SkillInfo> list = new ArrayList<>();
        for (SkillInfo info : catalog(ownerId).values()) {
            if (!SHARED_SKILL.equals(info.name())
                    && !SHARED_XML_GUIDE_SKILL.equals(info.name())
                    && info.selectable()
                    && ROUTER_SKILL_CATEGORY.equals(info.category())) {
                list.add(info);
            }
        }
        return list;
    }

    /**
     * The router's selectable skills fetched once, as both the prompt menu and the whitelist of
     * offered names. The router uses the whitelist to validate the returned skillName in-memory,
     * instead of hitting the catalog (DB) a second time.
     */
    public RouterCatalog routerCatalog(String ownerId) {
        StringBuilder sb = new StringBuilder();
        Set<String> names = new LinkedHashSet<>();
        for (SkillInfo info : selectableSkills(ownerId)) {
            sb.append("- ").append(sanitizeForPrompt(info.name(), 64))
              .append(": ").append(sanitizeForPrompt(info.description(), 200)).append('\n');
            names.add(info.name());
        }
        return new RouterCatalog(sb.toString(), names);
    }

    /** Router prompt menu paired with the set of skill names actually offered to the router. */
    public record RouterCatalog(String promptText, Set<String> skillNames) {
    }

    /**
     * Names of the skills selectable for this user (draw.io design skills, excluding shared/hidden).
     * This is the whitelist for BOTH router selection and drawer injection — never use {@code exists}
     * (full catalog) for those, or a caller could force a hidden/non-selectable skill's body in.
     */
    public Set<String> selectableSkillNames(String ownerId) {
        Set<String> names = new LinkedHashSet<>();
        for (SkillInfo info : selectableSkills(ownerId)) {
            names.add(info.name());
        }
        return names;
    }

    /** A compact menu (name + description) for prompting the router for a given user. */
    public String catalogText(String ownerId) {
        return routerCatalog(ownerId).promptText();
    }

    // Skill names/descriptions are user/platform-authored data that gets injected into the router
    // prompt. Treat them as untrusted: collapse to a single line, cap length, and defang common
    // prompt-injection framing so a description cannot issue instructions to the router.
    private String sanitizeForPrompt(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        oneLine = oneLine.replaceAll(
                "(?i)(ignore|disregard|override|forget)\\s+(all\\s+|the\\s+|previous\\s+|above\\s+)*"
                        + "(instruction|prompt|rule|context)s?",
                "[filtered]");
        oneLine = oneLine.replaceAll("(?i)\\b(system prompt|you are now|assistant:|user:|system:)\\b", "[filtered]");
        if (oneLine.length() > maxLen) {
            oneLine = oneLine.substring(0, maxLen) + "…";
        }
        return oneLine;
    }

    public boolean exists(String name, String ownerId) {
        return name != null && catalog(ownerId).containsKey(name.trim());
    }

    /** SKILL.md body (frontmatter stripped) for a discovered skill visible to the user, or empty. */
    public String body(String name, String ownerId) {
        if (name == null) {
            return "";
        }
        SkillInfo info = catalog(ownerId).get(name.trim());
        return info == null ? "" : info.body();
    }
}
