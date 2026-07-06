package org.zipp.ai.domain.agent.service.chat;

/**
 * How strongly a provider can enforce structured (JSON) output via the OpenAI-compatible
 * {@code response_format} parameter. Higher tiers subsume lower ones.
 *
 * <ul>
 *   <li>{@link #NONE} — do not send {@code response_format}; rely purely on the text parser and the
 *       enum hard-validation layer. Safe default for unknown / self-hosted endpoints that may 400 on
 *       an unsupported parameter.</li>
 *   <li>{@link #JSON_OBJECT} — {@code response_format={"type":"json_object"}}: guarantees a
 *       syntactically valid JSON object, but not field names or enum values.</li>
 *   <li>{@link #JSON_SCHEMA} — {@code response_format={"type":"json_schema","strict":true,...}}:
 *       constrains generation to an exact schema (fields, types, enums). OpenAI-family only.</li>
 * </ul>
 */
public enum StructuredOutputTier {
    NONE,
    JSON_OBJECT,
    JSON_SCHEMA;

    public boolean atLeast(StructuredOutputTier other) {
        return this.ordinal() >= other.ordinal();
    }
}
