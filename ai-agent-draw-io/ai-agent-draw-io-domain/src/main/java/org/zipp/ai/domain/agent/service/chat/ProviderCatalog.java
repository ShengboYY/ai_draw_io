package org.zipp.ai.domain.agent.service.chat;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Curated presets of the mainstream LLM providers plus their structured-output capability tier.
 *
 * <p>Two jobs:
 * <ol>
 *   <li>Feed the frontend a provider dropdown (base-url / completions-path / model hints) so users
 *       don't have to hand-type endpoints — see {@link #presets()}.</li>
 *   <li>Tell the routing/plugin layer how hard a given provider can enforce JSON output, so we only
 *       send {@code response_format} where it is actually supported — see {@link #tierFor(String)}.</li>
 * </ol>
 *
     * <p>Static so the non-Spring {@code MySpringAI} and the Spring beans (plugin, controller) share one
     * source of truth. Runtime overrides ({@link #downgrade(String, String)}) let one credential/endpoint
     * that unexpectedly rejects {@code response_format} be demoted without affecting sibling scopes.
 *
 * <p>Kept in code for now for simplicity; can be lifted into {@code application.yml} later without
 * changing callers.
 */
public final class ProviderCatalog {

    private ProviderCatalog() {
    }

    /** A single provider preset shown to the user and used to resolve capability. */
    public record Preset(String id,
                         String displayName,
                         String baseUrl,
                         String completionsPath,
                         List<String> models,
                         StructuredOutputTier tier) {
    }

    private static final List<Preset> PRESETS = List.of(
            new Preset("openai", "OpenAI",
                    "https://api.openai.com", "v1/chat/completions",
                    List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini"),
                    StructuredOutputTier.JSON_SCHEMA),
            new Preset("azure-openai", "Azure OpenAI",
                    "", "",
                    List.of("gpt-4o", "gpt-4o-mini"),
                    StructuredOutputTier.JSON_SCHEMA),
            new Preset("deepseek", "DeepSeek",
                    "https://api.deepseek.com", "v1/chat/completions",
                    List.of("deepseek-chat", "deepseek-reasoner"),
                    StructuredOutputTier.JSON_OBJECT),
            new Preset("qwen", "Qwen (DashScope)",
                    "https://dashscope.aliyuncs.com/compatible-mode", "v1/chat/completions",
                    List.of("qwen-plus", "qwen-max", "qwen-turbo"),
                    StructuredOutputTier.JSON_OBJECT),
            new Preset("moonshot", "Moonshot (Kimi)",
                    "https://api.moonshot.cn", "v1/chat/completions",
                    List.of("moonshot-v1-8k", "moonshot-v1-32k"),
                    StructuredOutputTier.JSON_OBJECT),
            new Preset("zhipu", "Zhipu (GLM)",
                    "https://open.bigmodel.cn/api/paas", "v4/chat/completions",
                    List.of("glm-4-plus", "glm-4"),
                    StructuredOutputTier.JSON_OBJECT),
            // Explicit escape hatch: user-supplied endpoint, capability unknown -> never force JSON.
            new Preset("custom", "Custom (OpenAI-compatible)",
                    "", "v1/chat/completions",
                    List.of(),
                    StructuredOutputTier.NONE));

    private static final Map<String, Preset> BY_ID = new ConcurrentHashMap<>();
    // Runtime demotions discovered when a concrete credential/endpoint 400s on response_format.
    private static final Map<String, StructuredOutputTier> OVERRIDES = new ConcurrentHashMap<>();

    static {
        for (Preset p : PRESETS) {
            BY_ID.put(p.id(), p);
        }
    }

    public static List<Preset> presets() {
        return PRESETS;
    }

    private static String normalize(String providerId) {
        return providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Base capability of the given provider. Unknown providers are {@link StructuredOutputTier#NONE}
     * (fail-safe: never send an unsupported parameter).
     */
    public static StructuredOutputTier tierFor(String providerId) {
        String id = normalize(providerId);
        if (id.isEmpty()) {
            return StructuredOutputTier.NONE;
        }
        Preset preset = BY_ID.get(id);
        return preset == null ? StructuredOutputTier.NONE : preset.tier();
    }

    /**
     * Capability for a specific saved credential or endpoint. Scoped overrides stop one bad credential
     * from demoting every other credential using the same provider id.
     */
    public static StructuredOutputTier tierFor(String providerId, String scopeKey) {
        String id = normalize(providerId);
        if (id.isEmpty()) {
            return StructuredOutputTier.NONE;
        }
        StructuredOutputTier override = OVERRIDES.get(overrideKey(id, scopeKey));
        return override == null ? tierFor(id) : override;
    }

    /**
     * Demote one concrete credential/endpoint by one tier after it rejected {@code response_format}.
     * Idempotent and monotonic for that scope (never re-promotes, never touches sibling scopes).
     */
    public static void downgrade(String providerId, String scopeKey) {
        String id = normalize(providerId);
        String scope = normalize(scopeKey);
        if (id.isEmpty() || scope.isEmpty()) {
            return;
        }
        StructuredOutputTier current = tierFor(id, scope);
        StructuredOutputTier demoted = switch (current) {
            case JSON_SCHEMA -> StructuredOutputTier.JSON_OBJECT;
            case JSON_OBJECT, NONE -> StructuredOutputTier.NONE;
        };
        OVERRIDES.put(overrideKey(id, scope), demoted);
    }

    private static String overrideKey(String providerId, String scopeKey) {
        return normalize(providerId) + "|" + normalize(scopeKey);
    }
}
