package org.zipp.ai.domain.agent.service.armory.matter.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioMutationResultPostProcessor;
import org.zipp.ai.domain.agent.service.armory.matter.skills.DrawioSkillAccessContext;
import org.zipp.ai.domain.agent.service.armory.matter.skills.SkillToolTraceContext;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class SpringToolCallbackAdkTool extends BaseTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final DrawioMutationResultPostProcessor DRAWIO_RESULT_POST_PROCESSOR =
            new DrawioMutationResultPostProcessor();

    private final ToolCallback toolCallback;
    private final FunctionDeclaration declaration;

    public SpringToolCallbackAdkTool(ToolCallback toolCallback) {
        super(toolCallback.getToolDefinition().name(), toolCallback.getToolDefinition().description());
        this.toolCallback = toolCallback;
        this.declaration = buildDeclaration(toolCallback.getToolDefinition());
    }

    public static List<BaseTool> fromCallbacks(Collection<ToolCallback> toolCallbacks) {
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            return List.of();
        }

        Map<String, BaseTool> toolsByName = new LinkedHashMap<>();
        for (ToolCallback callback : toolCallbacks) {
            if (callback == null || callback.getToolDefinition() == null) {
                continue;
            }
            String name = callback.getToolDefinition().name();
            if (StringUtils.isBlank(name) || toolsByName.containsKey(name)) {
                continue;
            }
            toolsByName.put(name, new SpringToolCallbackAdkTool(callback));
        }
        return new ArrayList<>(toolsByName.values());
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        return Optional.of(declaration);
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        return Single.fromCallable(() -> {
            // Route allowlists govern canvas mutation only; skill lookup remains available to the agent.
            if (toolContext != null && !DrawioToolAccessContext.allowsCanvasTool(toolContext.sessionId(), name())) {
                throw new IllegalStateException("Canvas tool is not allowed for this routed session: " + name());
            }
            AgentUsageTelemetryContext.RunContext runContext = resolveRunContext(toolContext).orElse(null);
            DrawioSkillAccessContext.SkillAccess skillAccess = resolveSkillAccess(toolContext).orElse(null);
            try (AgentUsageTelemetryContext.Scope runScope = bindRunContext(runContext);
                 DrawioSkillAccessContext.Scope skillScope = bindSkillAccess(skillAccess);
                SkillToolTraceContext.Scope traceScope = bindSkillTrace(runContext, toolContext)) {
                String response = toolCallback.call(OBJECT_MAPPER.writeValueAsString(args == null ? Map.of() : args));
                Map<String, Object> parsed = parseToolResponse(response);
                Map<String, Object> state = toolContext == null ? new LinkedHashMap<>() : toolContext.state();
                return DRAWIO_RESULT_POST_PROCESSOR.process(name(), args, parsed, state);
            }
        });
    }

    private AgentUsageTelemetryContext.Scope bindRunContext(AgentUsageTelemetryContext.RunContext runContext) {
        return runContext == null ? () -> { } : AgentUsageTelemetryContext.bind(runContext);
    }

    private DrawioSkillAccessContext.Scope bindSkillAccess(DrawioSkillAccessContext.SkillAccess skillAccess) {
        return skillAccess == null ? () -> { } : DrawioSkillAccessContext.bind(skillAccess);
    }

    private SkillToolTraceContext.Scope bindSkillTrace(AgentUsageTelemetryContext.RunContext runContext,
                                                       ToolContext toolContext) {
        String traceId = runContext == null ? "" : runContext.runId();
        String sessionId = toolContext == null ? "" : toolContext.sessionId();
        String invocationId = toolContext == null ? "" : toolContext.invocationId();
        return SkillToolTraceContext.bind(traceId, sessionId, invocationId);
    }

    private Optional<AgentUsageTelemetryContext.RunContext> resolveRunContext(ToolContext toolContext) {
        return toolContext == null
                ? AgentUsageTelemetryContext.current()
                : AgentUsageTelemetryContext.resolveInvocation(toolContext.invocationId());
    }

    private Optional<DrawioSkillAccessContext.SkillAccess> resolveSkillAccess(ToolContext toolContext) {
        return toolContext == null
                ? DrawioSkillAccessContext.current()
                : DrawioSkillAccessContext.resolve(toolContext.sessionId());
    }

    private FunctionDeclaration buildDeclaration(ToolDefinition definition) {
        FunctionDeclaration.Builder builder = FunctionDeclaration.builder()
                .name(definition.name())
                .description(StringUtils.defaultString(definition.description()));

        // ADK's Spring converter reads FunctionDeclaration.parameters(), so convert the JSON schema
        // into a GenAI Schema instead of storing it only as parametersJsonSchema.
        return builder.parameters(toSchema(parseInputSchema(definition.inputSchema()), true)).build();
    }

    private Object parseInputSchema(String inputSchema) {
        if (StringUtils.isBlank(inputSchema)) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            return OBJECT_MAPPER.readValue(inputSchema, Object.class);
        } catch (Exception ignored) {
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    private Schema toSchema(Object schemaObject, boolean root) {
        if (!(schemaObject instanceof Map<?, ?> schemaMap)) {
            return Schema.builder().type(root ? Type.Known.OBJECT : Type.Known.STRING).build();
        }

        Schema.Builder builder = Schema.builder();
        String type = StringUtils.defaultString(asString(schemaMap.get("type")));
        if (StringUtils.isBlank(type)) {
            type = schemaMap.containsKey("properties") || root ? "object" : "string";
        }
        builder.type(toKnownType(type));

        String description = asString(schemaMap.get("description"));
        if (StringUtils.isNotBlank(description)) {
            builder.description(description);
        }

        List<String> enumValues = stringList(schemaMap.get("enum"));
        if (!enumValues.isEmpty()) {
            builder.enum_(enumValues);
        }

        List<String> required = stringList(schemaMap.get("required"));
        if (!required.isEmpty()) {
            builder.required(required);
        }

        Object propertiesObject = schemaMap.get("properties");
        if (propertiesObject instanceof Map<?, ?> propertiesMap && !propertiesMap.isEmpty()) {
            Map<String, Schema> properties = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : propertiesMap.entrySet()) {
                String key = asString(entry.getKey());
                if (StringUtils.isNotBlank(key)) {
                    properties.put(key, toSchema(entry.getValue(), false));
                }
            }
            builder.properties(properties);
        }

        if (schemaMap.containsKey("items")) {
            builder.items(toSchema(schemaMap.get("items"), false));
        }

        return builder.build();
    }

    private Type.Known toKnownType(String type) {
        return switch (StringUtils.lowerCase(type)) {
            case "number" -> Type.Known.NUMBER;
            case "integer" -> Type.Known.INTEGER;
            case "boolean" -> Type.Known.BOOLEAN;
            case "array" -> Type.Known.ARRAY;
            case "object" -> Type.Known.OBJECT;
            case "null" -> Type.Known.NULL;
            default -> Type.Known.STRING;
        };
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream()
                .map(this::asString)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    private Map<String, Object> parseToolResponse(String response) {
        if (StringUtils.isBlank(response)) {
            return Map.of();
        }
        try {
            Object parsed = OBJECT_MAPPER.readValue(response, Object.class);
            if (parsed instanceof Map<?, ?>) {
                return OBJECT_MAPPER.convertValue(parsed, MAP_TYPE);
            }
            return Map.of("result", parsed);
        } catch (Exception ignored) {
            return Map.of("result", response);
        }
    }
}
