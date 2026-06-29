package org.zipp.ai.domain.agent.service.armory.matter.skills;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runtime registry of skills.
 *
 * <p>Skills are discovered from two sources and merged by name (external overrides built-in):
 * <ul>
 *   <li>built-in: {@code agent/skills/*\/SKILL.md} on the classpath (bundled in the jar);</li>
 *   <li>external: {@code <DRAWIO_SKILLS_DIR>/<skill>/SKILL.md} on the filesystem — a writable directory
 *       where users can add/edit/evolve their own skills at runtime.</li>
 * </ul>
 *
 * <p>The external directory is hot-reloaded: a lightweight signature (file count + max mtime) is
 * re-checked (throttled) and the catalog is rebuilt only when it changes, so new/edited SKILL.md
 * files take effect without a restart. Built-in classpath skills are immutable at runtime and scanned once.
 */
@Slf4j
@Service
public class SkillCatalogService {

    /** Always-applied shared design rules; excluded from the selectable catalog. */
    public static final String SHARED_SKILL = "drawio-visual-design";

    private static final String ROUTER_SKILL_CATEGORY = "drawio-design";
    private static final String CLASSPATH_LOCATION = "classpath*:agent/skills/*/SKILL.md";
    private static final Pattern FRONTMATTER = Pattern.compile("^\\s*---\\s*\\n(.*?)\\n---\\s*\\n?", Pattern.DOTALL);
    private static final Pattern FOLDER = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$");
    private static final long REFRESH_THROTTLE_MS = 2000;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** Writable external skills dir; empty = built-in skills only. */
    @Value("${DRAWIO_SKILLS_DIR:}")
    private String externalSkillsDir;

    public record SkillInfo(String name, String description, String body, String category, boolean selectable) {
    }

    private volatile Map<String, SkillInfo> classpathSkills; // immutable at runtime, scanned once
    private volatile Map<String, SkillInfo> catalog;         // merged built-in + external
    private volatile String externalSignature = "";
    private volatile long lastCheckAt = 0L;

    private Map<String, SkillInfo> catalog() {
        if (catalog != null && !hasExternalDir()) {
            return catalog; // built-in only never changes at runtime
        }
        long now = System.currentTimeMillis();
        if (catalog != null && now - lastCheckAt < REFRESH_THROTTLE_MS) {
            return catalog;
        }
        synchronized (this) {
            now = System.currentTimeMillis();
            if (catalog != null && now - lastCheckAt < REFRESH_THROTTLE_MS) {
                return catalog;
            }
            lastCheckAt = now;
            String signature = externalSignature();
            if (catalog == null || !signature.equals(externalSignature)) {
                externalSignature = signature;
                catalog = buildCatalog();
            }
            return catalog;
        }
    }

    private Map<String, SkillInfo> buildCatalog() {
        Map<String, SkillInfo> merged = new LinkedHashMap<>(classpathSkills());
        Map<String, SkillInfo> external = scanExternal();
        merged.putAll(external); // user skills add to / override built-ins by name
        log.info("Skill catalog loaded: {} skills (built-in {}, external {}) {}",
                merged.size(), classpathSkills().size(), external.size(), merged.keySet());
        return merged;
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

    /** Cheap fingerprint of the external dir so we only rebuild when something actually changed. */
    private String externalSignature() {
        if (!hasExternalDir()) {
            return "";
        }
        Path root = Path.of(externalSkillsDir.trim());
        if (!Files.isDirectory(root)) {
            return "absent";
        }
        long count = 0;
        long mtimeAccum = 0;
        try (Stream<Path> walk = Files.walk(root, 2)) {
            List<Path> files = walk.filter(p -> p.getFileName().toString().equals("SKILL.md")).toList();
            for (Path p : files) {
                count++;
                try {
                    mtimeAccum += Files.getLastModifiedTime(p).toMillis();
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            return "error";
        }
        return count + ":" + mtimeAccum;
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

    /** Skills the router may select: draw.io design skills only, excluding the always-applied shared skill. */
    public List<SkillInfo> selectableSkills() {
        List<SkillInfo> list = new ArrayList<>();
        for (SkillInfo info : catalog().values()) {
            if (!SHARED_SKILL.equals(info.name())
                    && info.selectable()
                    && ROUTER_SKILL_CATEGORY.equals(info.category())) {
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
