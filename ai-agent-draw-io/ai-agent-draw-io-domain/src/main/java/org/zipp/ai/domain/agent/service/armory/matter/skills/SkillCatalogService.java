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
    private static final String REFERENCE_FILE = "reference.md";
    private static final Pattern FRONTMATTER = Pattern.compile("^\\s*---\\s*\\n(.*?)\\n---\\s*\\n?", Pattern.DOTALL);
    private static final Pattern FOLDER = Pattern.compile(".*/skills/([^/]+)/SKILL\\.md$");
    private static final Pattern H2 = Pattern.compile("(?m)^##\\s+(.+)$");
    private static final Pattern H2_PRIORITY = Pattern.compile("\\[(P0|P1)]\\s*$");
    private static final long REFRESH_THROTTLE_MS = 2000;
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> KNOWN_DIAGRAM_TYPES = Set.of(
            "architecture", "flowchart", "sequence", "er", "uml_class", "usecase",
            "state", "concept", "shared");
    private static final Map<String, String> KNOWN_DRAWIO_DIAGRAM_TYPES = Map.ofEntries(
            Map.entry("drawio-architecture", "architecture"),
            Map.entry("drawio-flowchart", "flowchart"),
            Map.entry("drawio-sequence", "sequence"),
            Map.entry("drawio-er", "er"),
            Map.entry("drawio-uml", "uml_class"),
            Map.entry("drawio-usecase", "usecase"),
            Map.entry("drawio-state", "state"),
            Map.entry("drawio-concept", "concept"),
            Map.entry(SHARED_SKILL, "shared"),
            Map.entry(SHARED_XML_GUIDE_SKILL, "shared"));

    /** Optional writable external skills dir; empty = built-in + DB only. */
    @Value("${DRAWIO_SKILLS_DIR:}")
    private String externalSkillsDir;

    /** Optional DB-backed store for public + per-user skills; null = built-in + external only. */
    @Autowired(required = false)
    private SkillStore skillStore;

    public enum SkillSource {
        BUILT_IN,
        EXTERNAL_DIR,
        DB_PUBLIC,
        DB_PRIVATE
    }

    public record SkillInfo(String name,
                            String description,
                            String body,
                            String referenceBody,
                            String category,
                            String diagramType,
                            int schemaVersion,
                            boolean selectable,
                            SkillSource source,
                            List<String> validationErrors) {
        public SkillInfo {
            name = name == null ? "" : name.trim();
            description = description == null ? "" : description.trim();
            body = body == null ? "" : body.trim();
            referenceBody = referenceBody == null ? "" : referenceBody.trim();
            category = category == null ? "" : category.trim();
            diagramType = diagramType == null ? "" : diagramType.trim();
            source = source == null ? SkillSource.BUILT_IN : source;
            validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        }

        public SkillInfo(String name, String description, String body, String category, boolean selectable) {
            this(name, description, body, "", category, "", 0, selectable, SkillSource.BUILT_IN, List.of());
        }

        public boolean valid() {
            return validationErrors.isEmpty();
        }
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
            long invalidDrawioSkills = merged.values().stream()
                    .filter(info -> ROUTER_SKILL_CATEGORY.equals(info.category()))
                    .filter(info -> !info.valid())
                    .count();
            log.info("Skill catalog base loaded: {} skills (built-in {}) invalidDrawioSkills={} {}",
                    merged.size(), classpathSkills().size(), invalidDrawioSkills, merged.keySet());
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
                        logValidationWarnings(info);
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
                    logValidationWarnings(info);
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
        boolean selectable = skill.enabled() && !isSharedSkillName(skill.name());
        SkillSource source = skill.visibility() == SkillStore.Visibility.PUBLIC
                ? SkillSource.DB_PUBLIC
                : SkillSource.DB_PRIVATE;
        return parse(skill.body(), skill.name(), "",
                skill.description(), category, selectable, source);
    }

    /** Validate one dynamic skill exactly as it would enter the runtime catalog after saving. */
    public List<String> validateManagedSkill(String name, String description, String category, String body) {
        SkillInfo info = parse(body, name, "", description, category, true, SkillSource.DB_PRIVATE);
        if (info == null) {
            return List.of("name is required");
        }
        List<String> errors = new ArrayList<>(info.validationErrors());
        if (name != null && !name.isBlank() && !info.name().equals(name.trim())) {
            errors.add("frontmatter name must match requested name: " + name.trim());
        }
        return errors;
    }

    /** Force the shared base catalog to reload after runtime skill management writes. */
    public void invalidateCache() {
        base = null;
        lastBaseAt = 0L;
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
                    String referenceRaw = readOptionalRelative(resource, REFERENCE_FILE);
                    SkillInfo info = parse(raw, folderName(resource.getURL().toString()), referenceRaw,
                            "", "", true, SkillSource.BUILT_IN);
                    if (info != null) {
                        discovered.put(info.name(), info);
                        logValidationWarnings(info);
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
                        String referenceRaw = readOptionalFile(dir.resolve(REFERENCE_FILE));
                        SkillInfo info = parse(raw, dir.getFileName().toString(), referenceRaw,
                                "", "", true, SkillSource.EXTERNAL_DIR);
                        if (info != null) {
                            discovered.put(info.name(), info);
                            logValidationWarnings(info);
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

    private SkillInfo parse(String raw,
                            String folder,
                            String referenceRaw,
                            String fallbackDescription,
                            String fallbackCategory,
                            boolean fallbackSelectable,
                            SkillSource source) {
        String safeRaw = raw == null ? "" : raw;
        String name = folder;
        String description = fallbackDescription == null ? "" : fallbackDescription.trim();
        String body = safeRaw;
        String referenceBody = referenceRaw == null ? "" : referenceRaw.trim();
        String category = fallbackCategory == null ? "" : fallbackCategory.trim();
        String diagramType = "";
        int schemaVersion = 0;
        boolean selectable = fallbackSelectable;

        Matcher m = FRONTMATTER.matcher(safeRaw);
        if (m.find()) {
            String front = m.group(1);
            body = safeRaw.substring(m.end());
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
            // Prefer the normalized top-level fields, but keep reading legacy metadata.* skills.
            category = firstNonBlank(stringValue(metadata.get("category")),
                    stringValue(nestedMetadata.get("category")),
                    fallbackCategory);
            diagramType = firstNonBlank(stringValue(metadata.get("diagramType")),
                    stringValue(nestedMetadata.get("diagramType")));
            schemaVersion = intValue(metadata.get("schemaVersion"),
                    intValue(nestedMetadata.get("schemaVersion"), 0));
            selectable = booleanValue(metadata.get("selectable"),
                    booleanValue(nestedMetadata.get("selectable"), fallbackSelectable));
        }

        if (name == null || name.isBlank()) {
            return null;
        }
        String normalizedName = name.trim();
        if (schemaVersion == 0 && isKnownDrawioSkill(normalizedName, category)) {
            schemaVersion = 1;
        }
        if ((diagramType == null || diagramType.isBlank()) && ROUTER_SKILL_CATEGORY.equals(category)) {
            diagramType = knownDiagramType(normalizedName);
        }

        List<String> validationErrors = validateSkill(
                normalizedName,
                body,
                referenceBody,
                category,
                diagramType,
                schemaVersion);
        return new SkillInfo(normalizedName, description, body.trim(), referenceBody,
                category == null ? "" : category.trim(), diagramType, schemaVersion,
                selectable, source, validationErrors);
    }

    private List<String> validateSkill(String name,
                                       String body,
                                       String referenceBody,
                                       String category,
                                       String diagramType,
                                       int schemaVersion) {
        if (!ROUTER_SKILL_CATEGORY.equals(category)) {
            return List.of();
        }

        List<String> errors = new ArrayList<>();
        if (schemaVersion < 1) {
            errors.add("schemaVersion must be >= 1");
        }
        if (diagramType == null || diagramType.isBlank()) {
            errors.add("diagramType is required");
        } else if (!KNOWN_DIAGRAM_TYPES.contains(diagramType.trim())) {
            errors.add("diagramType is not supported: " + diagramType.trim());
        }
        validatePrioritizedSections("SKILL.md", body, errors);
        if (referenceBody != null && !referenceBody.isBlank()) {
            validatePrioritizedSections(REFERENCE_FILE, referenceBody, errors);
        }
        return errors;
    }

    private void validatePrioritizedSections(String label, String body, List<String> errors) {
        Matcher matcher = H2.matcher(body == null ? "" : body);
        int count = 0;
        while (matcher.find()) {
            count++;
            String title = matcher.group(1).trim();
            if (!H2_PRIORITY.matcher(title).find()) {
                errors.add(label + " H2 section must end with [P0] or [P1]: " + title);
            }
        }
        if (count == 0) {
            errors.add(label + " must define at least one ## section");
        }
    }

    private boolean isKnownDrawioSkill(String name, String category) {
        return ROUTER_SKILL_CATEGORY.equals(category) && KNOWN_DRAWIO_DIAGRAM_TYPES.containsKey(name);
    }

    private String knownDiagramType(String name) {
        return KNOWN_DRAWIO_DIAGRAM_TYPES.getOrDefault(name, "");
    }

    private boolean isSharedSkillName(String name) {
        return SHARED_SKILL.equals(name) || SHARED_XML_GUIDE_SKILL.equals(name);
    }

    private String readOptionalRelative(Resource resource, String path) {
        try {
            Resource relative = resource.createRelative(path);
            if (relative.exists() && relative.isReadable()) {
                return StreamUtils.copyToString(relative.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private String readOptionalFile(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private void logValidationWarnings(SkillInfo info) {
        if (info != null && ROUTER_SKILL_CATEGORY.equals(info.category()) && !info.valid()) {
            log.warn("Skill catalog loaded invalid drawio skill: name={} source={} selectable={} errors={}",
                    info.name(), info.source(), info.selectable(), info.validationErrors());
        }
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

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
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
        return selectableSkills(catalog(ownerId));
    }

    private List<SkillInfo> selectableSkills(Map<String, SkillInfo> visibleCatalog) {
        List<SkillInfo> list = new ArrayList<>();
        for (SkillInfo info : visibleCatalog.values()) {
            if (!SHARED_SKILL.equals(info.name())
                    && !SHARED_XML_GUIDE_SKILL.equals(info.name())
                    && info.selectable()
                    && info.valid()
                    && ROUTER_SKILL_CATEGORY.equals(info.category())) {
                list.add(info);
            }
        }
        // Stable ordering makes router prompts and checkpoint digests reproducible across stores.
        return list.stream().sorted(java.util.Comparator.comparing(SkillInfo::name)).toList();
    }

    /**
     * Reads the owner-visible catalog once for V2 routing and later deterministic validation.
     * Skill bodies remain in this domain snapshot only long enough for content digest calculation.
     */
    public RuntimeCatalog runtimeCatalog(String ownerId) {
        Map<String, SkillInfo> visible = catalog(ownerId);
        List<SkillInfo> selectable = selectableSkills(visible);
        StringBuilder prompt = new StringBuilder();
        for (SkillInfo info : selectable) {
            prompt.append("- ").append(sanitizeForPrompt(info.name(), 64))
                    .append(": ").append(sanitizeForPrompt(info.description(), 200)).append('\n');
        }
        List<SkillInfo> shared = new ArrayList<>();
        // Shared ordering is an explicit runtime contract: XML rules precede visual guidance.
        for (String name : List.of(SHARED_XML_GUIDE_SKILL, SHARED_SKILL)) {
            SkillInfo info = visible.get(name);
            if (info != null && info.valid() && ROUTER_SKILL_CATEGORY.equals(info.category())) {
                shared.add(info);
            }
        }
        return new RuntimeCatalog(prompt.toString(), selectable, shared);
    }

    /** Single-read V2 catalog projection, including required shared skills. */
    public record RuntimeCatalog(
            String promptText,
            List<SkillInfo> selectableSkills,
            List<SkillInfo> sharedSkills
    ) {
        public RuntimeCatalog {
            promptText = promptText == null ? "" : promptText;
            selectableSkills = List.copyOf(selectableSkills == null ? List.of() : selectableSkills);
            sharedSkills = List.copyOf(sharedSkills == null ? List.of() : sharedSkills);
        }
    }

    /**
     * The router's selectable skills fetched once, as both the prompt menu and the whitelist of
     * offered names. The router uses the whitelist to validate the returned skillName in-memory,
     * instead of hitting the catalog (DB) a second time.
     */
    public RouterCatalog routerCatalog(String ownerId) {
        RuntimeCatalog runtime = runtimeCatalog(ownerId);
        Set<String> names = new LinkedHashSet<>();
        for (SkillInfo info : runtime.selectableSkills()) {
            names.add(info.name());
        }
        return new RouterCatalog(runtime.promptText(), names);
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

    /** Full parsed metadata for diagnostics and tests, or null when the skill is not visible. */
    public SkillInfo info(String name, String ownerId) {
        if (name == null) {
            return null;
        }
        return catalog(ownerId).get(name.trim());
    }

    /** SKILL.md body (frontmatter stripped) for a discovered skill visible to the user, or empty. */
    public String body(String name, String ownerId) {
        SkillInfo info = info(name, ownerId);
        return info == null ? "" : info.body();
    }

    /** Optional reference.md body for a discovered skill visible to the user, or empty. */
    public String referenceBody(String name, String ownerId) {
        SkillInfo info = info(name, ownerId);
        return info == null ? "" : info.referenceBody();
    }
}
