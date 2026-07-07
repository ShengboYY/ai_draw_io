package org.zipp.ai.domain.agent.service.armory.matter.skills;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses SKILL.md bodies into stable sections while keeping Markdown as the authoring format.
 */
public class SkillDocumentParser {

    private static final Pattern HEADING = Pattern.compile("(?m)^(#{2,6})\\s+(.+?)\\s*$");
    private static final Pattern PRIORITY = Pattern.compile("\\[(P\\d+)]", Pattern.CASE_INSENSITIVE);
    private static final Set<String> P0_SECTION_IDS = Set.of(
            "when-to-use",
            "view-selection",
            "shape-vocabulary",
            "notation-vocabulary",
            "layout-recipes",
            "rules",
            "edge-rules",
            "output-scope",
            "xml-rules",
            "layout-contract",
            "layout-modes-global-draw-io-layout-contract",
            "layout-modes",
            "palette",
            "typography-and-shapes",
            "golden-example"
    );
    private static final Set<String> P1_SECTION_IDS = Set.of(
            "anti-patterns",
            "checklist",
            "final-self-check",
            "pre-flight-check-before-every-tool-call",
            "pre-flight-check",
            "house-patterns",
            "modern-product-profile",
            "container-view-example"
    );

    public SkillDocument parse(String body) {
        String normalized = StringUtils.trimToEmpty(body);
        Matcher matcher = HEADING.matcher(normalized);
        List<HeadingMatch> headings = new ArrayList<>();
        while (matcher.find()) {
            headings.add(new HeadingMatch(
                    matcher.start(),
                    matcher.end(),
                    matcher.group(1).length(),
                    matcher.group(2).trim()));
        }
        if (headings.isEmpty()) {
            return new SkillDocument(normalized, normalized, List.of());
        }

        String intro = normalized.substring(0, headings.get(0).start()).trim();
        List<SkillSection> sections = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            HeadingMatch heading = headings.get(i);
            int nextStart = i + 1 < headings.size() ? headings.get(i + 1).start() : normalized.length();
            String rawSection = normalized.substring(heading.start(), nextStart).trim();
            String sectionBody = normalized.substring(heading.end(), nextStart).trim();
            String priority = explicitPriority(heading.title());
            String title = stripPriority(heading.title());
            String id = sectionId(title);
            if (priority == null) {
                priority = inferredPriority(id);
            }
            sections.add(new SkillSection(i, id, title, priority, heading.level(), sectionBody, rawSection.length()));
        }
        return new SkillDocument(normalized, intro, List.copyOf(sections));
    }

    private String explicitPriority(String title) {
        Matcher matcher = PRIORITY.matcher(title);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private String stripPriority(String title) {
        return PRIORITY.matcher(title).replaceAll("").trim();
    }

    private String inferredPriority(String id) {
        if (P1_SECTION_IDS.contains(id)) {
            return "P1";
        }
        if (P0_SECTION_IDS.contains(id) || id.contains("golden-example")) {
            return "P0";
        }
        return "P1";
    }

    private String sectionId(String title) {
        String lower = StringUtils.defaultString(title).toLowerCase(Locale.ROOT);
        String normalized = lower.replace("&", " and ");
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? "section" : normalized;
    }

    private record HeadingMatch(int start, int end, int level, String title) {
    }

    public record SkillDocument(String body, String intro, List<SkillSection> sections) {
        public boolean hasSections() {
            return !sections.isEmpty();
        }

        public Optional<SkillSection> section(String sectionId) {
            String normalized = StringUtils.trimToEmpty(sectionId);
            if (normalized.isBlank()) {
                return Optional.empty();
            }
            return sections.stream()
                    .filter(section -> normalized.equals(section.id()))
                    .findFirst();
        }
    }

    public record SkillSection(int index, String id, String title, String priority, int level, String body,
                               int chars) {
    }
}
