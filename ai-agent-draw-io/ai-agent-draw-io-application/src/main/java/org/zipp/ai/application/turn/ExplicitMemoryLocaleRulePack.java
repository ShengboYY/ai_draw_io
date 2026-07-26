package org.zipp.ai.application.turn;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioned, high-precision locale rules for explicit Memory confirmation.
 *
 * <p>Every rule requires both a memory verb and a decision noun. This keeps ordinary chat from
 * creating pending Memory while supporting natural confirmation phrases without UI buttons.</p>
 */
final class ExplicitMemoryLocaleRulePack {

    static final String PACK_VERSION = "MEMORY_EXPLICIT_LOCALE_PACK_V2_20260727";

    private static final List<Rule> RULES = List.of(
            rule("zh-Hans", "ZH_HANS",
                    "^(?:(?:请|麻烦)\\s*)?(?:帮我\\s*)?(?:记住|记下|保存)\\s*(?:这个|这项)?"
                            + "(?:决定|决策|约定)(?:\\s*[:：]\\s*|\\s+)(.+)$", 0),
            rule("zh-Hant", "ZH_HANT",
                    "^(?:(?:請|麻煩)\\s*)?(?:幫我\\s*)?(?:記住|記下|保存)\\s*(?:這個|這項)?"
                            + "(?:決定|決策|約定)(?:\\s*[:：]\\s*|\\s+)(.+)$", 0),
            rule("en", "EN",
                    "^(?:please\\s+)?(?:remember|record|save)\\s+(?:this\\s+)?"
                            + "(?:decision|agreement)(?:\\s*:\\s*|\\s+)(.+)$",
                    Pattern.CASE_INSENSITIVE),
            rule("es", "ES",
                    "^(?:por\\s+favor\\s+)?(?:recuerda|registra|guarda)\\s+(?:esta\\s+)?"
                            + "(?:decisi[oó]n|acuerdo)(?:\\s*:\\s*|\\s+)(.+)$",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            rule("fr", "FR",
                    "^(?:s['’]il\\s+vous\\s+pla[iî]t\\s+)?(?:m[eé]morise|enregistre|retiens)\\s+"
                            + "(?:cette\\s+)?(?:d[eé]cision|convention)(?:\\s*:\\s*|\\s+)(.+)$",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            rule("de", "DE",
                    "^(?:bitte\\s+)?(?:merke|speichere|notiere)\\s+(?:dir\\s+)?(?:diese\\s+)?"
                            + "(?:entscheidung|vereinbarung)(?:\\s*:\\s*|\\s+)(.+)$",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            rule("ja", "JA",
                    "^(?:この)?(?:決定|方針|合意)(?:を)?(?:覚えて|記憶して|保存して)"
                            + "(?:ください)?(?:\\s*[:：]\\s*|\\s+)(.+)$", 0),
            rule("ko", "KO",
                    "^(?:이\\s*)?(?:결정|합의|규칙)(?:을|를)?\\s*(?:기억해|기억해줘|저장해|기록해)"
                            + "(?:\\s*[:：]\\s*|\\s+)(.+)$", 0));

    private ExplicitMemoryLocaleRulePack() {
    }

    static Optional<Match> match(String content) {
        String normalized = content == null ? "" : content.trim();
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(normalized);
            if (!matcher.matches()) {
                continue;
            }
            String canonicalText = matcher.group(1).trim();
            if (canonicalText.isEmpty()) {
                return Optional.empty();
            }
            String matchedSpan = matcher.group(0).substring(0, matcher.start(1)).trim();
            return Optional.of(new Match(
                    rule.version(), rule.locale(), matchedSpan, canonicalText));
        }
        return Optional.empty();
    }

    private static Rule rule(String locale, String ruleName, String regex, int flags) {
        return new Rule(
                PACK_VERSION + "_" + ruleName,
                locale,
                Pattern.compile(regex, flags));
    }

    record Match(String ruleVersion, String locale, String matchedSpan, String canonicalText) {
    }

    private record Rule(String version, String locale, Pattern pattern) {
    }
}
