package org.zipp.ai.domain.agent.service.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of named JSON Schemas used for {@code response_format=json_schema}.
 *
 * <p>Decouples the generic prompt converter from any specific agent: the owning agent (e.g. the
 * intent router) registers its schema under an id at class-load time, and the converter looks it up
 * by the id carried on the request header. The converter therefore never imports routing-specific
 * types.
 */
public final class StructuredOutputSchemas {

    private StructuredOutputSchemas() {
    }

    private static final Map<String, String> SCHEMAS = new ConcurrentHashMap<>();

    public static void register(String id, String schemaJson) {
        if (id != null && !id.isBlank() && schemaJson != null && !schemaJson.isBlank()) {
            SCHEMAS.put(id, schemaJson);
        }
    }

    /** The schema JSON for the id, or {@code null} if none is registered. */
    public static String get(String id) {
        return id == null ? null : SCHEMAS.get(id);
    }
}
