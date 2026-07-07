package org.zipp.ai.domain.agent.service.intent;

import com.alibaba.fastjson.JSON;
import org.zipp.ai.domain.agent.service.chat.StructuredOutputSchemas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single source of truth for the intent-router output contract.
 *
 * <p>The same enum sets drive two things that must never drift apart:
 * <ul>
 *   <li>the runtime hard-validation in {@link DefaultIntentRoutingService} (coerce invalid values to
 *       safe defaults), and</li>
 *   <li>the {@code json_schema} handed to capable providers so the model cannot emit an invalid value
 *       in the first place ({@link #ROUTER_SCHEMA_ID}).</li>
 * </ul>
 *
 * <p>Note the deliberate split for diagram type: {@link #ROUTER_DIAGRAM_TYPE_ENUM} is what the model
 * is allowed to <em>emit</em> (prompt-facing aliases like {@code uml_class}); {@link #CANONICAL_DIAGRAM_TYPES}
 * is what the rest of the system sees after {@code normalizeDiagramType} maps the aliases. The schema
 * constrains the former; validation enforces the latter.
 */
public final class IntentRoutingContract {

    private IntentRoutingContract() {
    }

    public static final List<String> ROUTE_TYPES =
            List.of("answer_only", "clarify", "create_new", "edit_existing", "optimize_layout", "review_only");
    public static final List<String> ANSWER_MODES = List.of(
            "none", "general", "capability", "canvas_summary",
            "quality_review", "semantic_review", "quality_and_semantic_review");

    /** Canonical diagram types seen downstream (after alias normalization). Used by validation. */
    public static final Set<String> CANONICAL_DIAGRAM_TYPES = Set.of(
            "none", "basic", "uml", "flowchart", "architecture", "sequence",
            "er", "usecase", "state", "mindmap", "blank", "illustration", "others");

    /** Prompt-facing diagram types the router is allowed to emit. Used by the json_schema enum. */
    public static final List<String> ROUTER_DIAGRAM_TYPE_ENUM = List.of(
            "none", "basic", "uml_class", "flowchart", "architecture",
            "sequence", "er", "usecase", "state", "concept");

    public static final String ROUTER_SCHEMA_ID = "intent_router";

    static {
        ensureRegistered();
    }

    /**
     * Return the router schema id only after explicitly registering the schema. Callers must use this
     * method instead of the compile-time constant when they need the registry populated.
     */
    public static String routerSchemaId() {
        ensureRegistered();
        return ROUTER_SCHEMA_ID;
    }

    /** Idempotent registration for paths that only need the schema registry side effect. */
    public static void ensureRegistered() {
        StructuredOutputSchemas.register(ROUTER_SCHEMA_ID, routerJsonSchema());
    }

    /**
     * Build the OpenAI strict json_schema for the router result. Strict mode requires every property
     * to be listed in {@code required} and {@code additionalProperties:false}. {@code skillName} stays
     * a free string (the catalog is dynamic and per-user); it is whitelisted at validation time.
     */
    public static String routerJsonSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("routeType", enumProp(ROUTE_TYPES));
        properties.put("diagramType", enumProp(ROUTER_DIAGRAM_TYPE_ENUM));
        properties.put("skillName", stringProp());
        properties.put("needsCanvasQuality", boolProp());
        properties.put("needsSemanticReview", boolProp());
        properties.put("answerMode", enumProp(ANSWER_MODES));
        properties.put("answer", stringProp());
        properties.put("reason", stringProp());

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("additionalProperties", false);
        return JSON.toJSONString(schema);
    }

    private static Map<String, Object> enumProp(java.util.Collection<String> values) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string");
        prop.put("enum", List.copyOf(values));
        return prop;
    }

    private static Map<String, Object> stringProp() {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string");
        return prop;
    }

    private static Map<String, Object> boolProp() {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "boolean");
        return prop;
    }
}
