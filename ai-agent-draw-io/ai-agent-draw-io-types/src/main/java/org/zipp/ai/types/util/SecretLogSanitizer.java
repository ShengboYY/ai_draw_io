package org.zipp.ai.types.util;

import java.util.regex.Pattern;

public final class SecretLogSanitizer {

    private static final String MASK = "***";

    private static final Pattern JSON_SECRET_FIELD = Pattern.compile(
            "(\"(?:apiKey|customApiKey|api[_-]?key|authorization|userId|workspaceId)\"\\s*:\\s*\")([^\"]*)(\")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "([?&](?:api[_-]?key|apikey|access[_-]?token|token|userId|workspaceId)=)[^&\\s\"\\]]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(Bearer\\s+)[A-Za-z0-9._~+\\-/=]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SK_TOKEN = Pattern.compile("sk-[A-Za-z0-9_-]+");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "\\b(apiKey|customApiKey|api[_-]?key|userId|workspaceId)\\s*=\\s*([^,\\s\\]\"}]+)",
            Pattern.CASE_INSENSITIVE);

    private SecretLogSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null) {
            return null;
        }

        // Keep this centralized so every log path masks fields, query params, and auth headers consistently.
        String sanitized = JSON_SECRET_FIELD.matcher(text).replaceAll("$1" + MASK + "$3");
        sanitized = QUERY_SECRET.matcher(sanitized).replaceAll("$1" + MASK);
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("$1" + MASK);
        sanitized = SK_TOKEN.matcher(sanitized).replaceAll("sk-" + MASK);
        return KEY_VALUE_SECRET.matcher(sanitized).replaceAll("$1=" + MASK);
    }

    public static String maskSecret(String secret) {
        return isBlank(secret) ? "(none)" : MASK;
    }

    public static String maskCapability(String capability) {
        if (isBlank(capability)) {
            return "";
        }
        String value = capability.trim();
        if (value.length() <= 6) {
            return MASK;
        }
        return value.substring(0, 4) + MASK + value.substring(value.length() - 2);
    }

    public static String maskAuthorization(String apiKey) {
        return isBlank(apiKey) ? "(none)" : "Bearer " + MASK;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
