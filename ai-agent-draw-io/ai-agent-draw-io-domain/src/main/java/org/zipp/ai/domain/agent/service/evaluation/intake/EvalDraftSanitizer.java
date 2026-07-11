package org.zipp.ai.domain.agent.service.evaluation.intake;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Fail-closed sanitizer that runs before any Trace-to-Eval model invocation. */
public class EvalDraftSanitizer {
    public static final String VERSION = "eval-sanitizer-v2";
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d ()-]{7,}\\d)(?!\\d)");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s\\\"]+");
    private static final Pattern SECRET = Pattern.compile("(?i)(authorization|api[_-]?key|token|password|secret)\\s*[:=]\\s*[^\\s,;]+|\\b(?:sk|api)[-_][A-Za-z0-9_-]{8,}\\b");
    private static final Pattern LABELED_ENTITY = Pattern.compile(
            "(?iu)\\b(customer|client|company|organization|organisation|project|product|service|person|user|account|tenant|workspace)"
                    + "\\s*(?:name\\s*)?(?::|=|#|\\bis\\b)\\s*"
                    + "([\\p{L}\\p{N}][\\p{L}\\p{N} ._&'/-]{1,80}?)(?=\\s+(?:and|with|requested|reported|uses|at)\\b|[,;.!?\\n]|$)");
    private static final Pattern CHINESE_LABELED_ENTITY = Pattern.compile(
            "(客户|公司|组织|项目|产品|服务|人员|用户|账号|租户|工作区)(?:名称)?\\s*[：:=]\\s*"
                    + "([\\p{L}\\p{N}._&'/-]{2,40})(?=[，。；！？、\\s]|$)");
    private static final Pattern COMPANY_SUFFIX = Pattern.compile(
            "(?i)\\b([A-Z][A-Za-z0-9&_.-]*(?:\\s+[A-Z][A-Za-z0-9&_.-]*){0,3}\\s+(?:Inc|Corp|Corporation|Ltd|LLC|Limited|Pty))\\.?\\b");
    private static final Pattern UNSAFE_UNSTRUCTURED = Pattern.compile(
            "(?i)\\b(confidential|trade[ -]?secret|raw production payload|real customer data|unredacted)\\b");

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
        sanitized = replaceLabeledEntities(sanitized, LABELED_ENTITY, removed);
        sanitized = replaceLabeledEntities(sanitized, CHINESE_LABELED_ENTITY, removed);
        sanitized = replaceCompanies(sanitized, removed);
        if (UNSAFE_UNSTRUCTURED.matcher(sanitized).find()) {
            return new Result(false, null, appendCategory(removed, "unclassified_sensitive_text"));
        }
        return new Result(StringUtils.isNotBlank(sanitized), sanitized, removed);
    }

    private String replaceLabeledEntities(String value, Pattern pattern, List<String> removed) {
        java.util.regex.Matcher matcher = pattern.matcher(value);
        StringBuffer result = new StringBuffer();
        Map<String, String> placeholders = new LinkedHashMap<>();
        Map<String, Integer> counters = new LinkedHashMap<>();
        while (matcher.find()) {
            String category = category(matcher.group(1));
            String identity = category + ":" + matcher.group(2).trim().toLowerCase(Locale.ROOT);
            String placeholder = placeholders.computeIfAbsent(identity,
                    ignored -> "[" + category + "-" + counters.merge(category, 1, Integer::sum) + "]");
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(placeholder));
            if (!removed.contains("business_entity")) removed.add("business_entity");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String replaceCompanies(String value, List<String> removed) {
        java.util.regex.Matcher matcher = COMPANY_SUFFIX.matcher(value);
        StringBuffer result = new StringBuffer();
        Map<String, String> placeholders = new LinkedHashMap<>();
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String placeholder = placeholders.computeIfAbsent(key, ignored -> "[COMPANY-" + (placeholders.size() + 1) + "]");
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(placeholder));
            if (!removed.contains("business_entity")) removed.add("business_entity");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String category(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (normalized.matches("customer|client|客户")) return "CUSTOMER";
        if (normalized.matches("company|organization|organisation|公司|组织")) return "COMPANY";
        if (normalized.matches("service|服务")) return "INTERNAL-SERVICE";
        if (normalized.matches("person|user|人员|用户")) return "PERSON";
        if (normalized.matches("account|账号")) return "ACCOUNT";
        if (normalized.matches("tenant|workspace|租户|工作区")) return "WORKSPACE";
        if (normalized.matches("product|产品")) return "PRODUCT";
        return "PROJECT";
    }

    private List<String> appendCategory(List<String> values, String category) {
        if (!values.contains(category)) values.add(category);
        return values;
    }

    private String replace(Pattern pattern, String value, String replacement, String category, List<String> removed) {
        java.util.regex.Matcher matcher = pattern.matcher(value);
        if (matcher.find()) removed.add(category);
        return matcher.replaceAll(replacement);
    }

    public record Result(boolean safeForModel, String content, List<String> removedCategories) { }
}
