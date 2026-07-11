package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Fail-closed sanitizer that runs before any Trace-to-Eval model invocation. */
public class EvalDraftSanitizer {
    public static final String VERSION = "eval-sanitizer-v1";
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d ()-]{7,}\\d)(?!\\d)");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\\\"]+");
    private static final Pattern SECRET = Pattern.compile("(?i)(authorization|api[_-]?key|token|password|secret)\\s*[:=]\\s*[^\\s,;]+|\\b(?:sk|api)[-_][A-Za-z0-9_-]{8,}\\b");

    public Result sanitize(List<String> contents) {
        if (contents == null || contents.isEmpty()) return new Result(false, null, List.of("no_content"));
        String joined = StringUtils.left(String.join("\n", contents), 12_000);
        if (StringUtils.containsIgnoreCase(joined, "<mxGraphModel") || StringUtils.containsIgnoreCase(joined, "<mxfile")) {
            return new Result(false, null, List.of("diagram_xml_requires_manual_synthesis"));
        }
        List<String> removed = new ArrayList<>();
        String sanitized = replace(SECRET, joined, "[SECRET]", "secret", removed);
        sanitized = replace(EMAIL, sanitized, "[EMAIL]", "email", removed);
        sanitized = replace(PHONE, sanitized, "[PHONE]", "phone", removed);
        sanitized = replace(URL, sanitized, "[URL]", "url", removed);
        return new Result(StringUtils.isNotBlank(sanitized), sanitized, removed);
    }

    private String replace(Pattern pattern, String value, String replacement, String category, List<String> removed) {
        java.util.regex.Matcher matcher = pattern.matcher(value);
        if (matcher.find()) removed.add(category);
        return matcher.replaceAll(replacement);
    }

    public record Result(boolean safeForModel, String content, List<String> removedCategories) { }
}
