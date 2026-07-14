package org.zipp.ai.domain.agent.service.armory.matter.tool;

import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Session-scoped enforcement for the route-specific canvas tool allowlist. */
public final class DrawioToolAccessContext {

    private static final ConcurrentMap<String, Set<String>> SESSION_TOOLS = new ConcurrentHashMap<>();

    private DrawioToolAccessContext() {
    }

    public static void bindSession(String sessionId, Collection<String> allowedTools) {
        if (StringUtils.isBlank(sessionId)) return;
        SESSION_TOOLS.put(sessionId, Set.copyOf(new LinkedHashSet<>(allowedTools == null ? Set.of() : allowedTools)));
    }

    public static boolean allowsCanvasTool(String sessionId, String toolName) {
        if (!DrawioCanvasToolNames.CONSOLIDATED_TOOL_NAMES.contains(toolName)) return true;
        Optional<Set<String>> allowed = Optional.ofNullable(SESSION_TOOLS.get(sessionId));
        // Tool calls outside a routed product session retain their agent-level declarations.
        return allowed.isEmpty() || allowed.get().contains(toolName);
    }

    public static void clearSession(String sessionId) {
        if (StringUtils.isNotBlank(sessionId)) SESSION_TOOLS.remove(sessionId);
    }
}
