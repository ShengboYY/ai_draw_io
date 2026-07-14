package org.zipp.ai.domain.agent.service.armory.matter.tool;

import org.apache.commons.lang3.StringUtils;
import org.zipp.ai.domain.agent.service.armory.matter.mcp.server.DrawioCanvasToolNames;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Run-owned enforcement for route-specific initial and repair canvas tools. */
public final class DrawioToolAccessContext {

    private static final ConcurrentMap<String, String> SESSION_OWNERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, SessionToolPolicy> RUN_POLICIES = new ConcurrentHashMap<>();

    private DrawioToolAccessContext() {
    }

    public static void openSession(String sessionId, String runId) {
        if (StringUtils.isAnyBlank(sessionId, runId)) return;
        String existingOwner = SESSION_OWNERS.putIfAbsent(sessionId, runId);
        if (existingOwner != null && !existingOwner.equals(runId)) {
            throw new IllegalStateException("Canvas session already has an active routed run");
        }
        RUN_POLICIES.putIfAbsent(runId, new SessionToolPolicy(
                ToolPolicy.of(List.of(), List.of()),
                ToolPhase.INITIAL_AVAILABLE));
    }

    public static void applyToolPolicy(String runId, ToolPolicy toolPolicy) {
        if (StringUtils.isBlank(runId)) return;
        RUN_POLICIES.compute(runId, (ignored, existing) -> {
            if (existing == null) {
                throw new IllegalStateException("Canvas routed run has not opened its session");
            }
            return new SessionToolPolicy(toolPolicy, ToolPhase.INITIAL_AVAILABLE);
        });
    }

    public static boolean tryStartCanvasTool(String sessionId, String runId, String toolName) {
        if (!DrawioCanvasToolNames.CONSOLIDATED_TOOL_NAMES.contains(toolName)) return true;
        String policyOwner = activeOwner(sessionId);
        if (policyOwner == null) {
            // Tool calls outside a routed product run retain their agent-level declarations.
            return true;
        }
        // Routed calls fail closed when telemetry cannot prove which run owns the session.
        if (!policyOwner.equals(runId)) return false;

        AtomicBoolean allowed = new AtomicBoolean(false);
        RUN_POLICIES.computeIfPresent(policyOwner, (ignored, policy) -> {
            if (policy.phase() == ToolPhase.INITIAL_AVAILABLE
                    && policy.toolPolicy().initialTools().contains(toolName)) {
                allowed.set(true);
                return policy.withPhase(ToolPhase.INITIAL_IN_FLIGHT);
            }
            if (policy.phase() == ToolPhase.REPAIR
                    && policy.toolPolicy().repairTools().contains(toolName)) {
                allowed.set(true);
            }
            return policy;
        });
        return allowed.get();
    }

    public static void finishCanvasTool(String sessionId, String runId, boolean mutationApplied) {
        String policyOwner = activeOwner(sessionId);
        if (policyOwner == null) return;
        if (!policyOwner.equals(runId)) return;
        RUN_POLICIES.computeIfPresent(policyOwner, (ignored, policy) -> {
            if (policy.phase() != ToolPhase.INITIAL_IN_FLIGHT) return policy;
            return policy.withPhase(mutationApplied ? ToolPhase.REPAIR : ToolPhase.INITIAL_AVAILABLE);
        });
    }

    public static boolean closeSession(String sessionId, String runId) {
        if (StringUtils.isAnyBlank(sessionId, runId)) return true;
        String owner = SESSION_OWNERS.get(sessionId);
        if (owner != null && !owner.equals(runId)) return false;
        RUN_POLICIES.remove(runId);
        SESSION_OWNERS.remove(sessionId, runId);
        return true;
    }

    private static String activeOwner(String sessionId) {
        return StringUtils.isBlank(sessionId) ? null : SESSION_OWNERS.get(sessionId);
    }

    public record ToolPolicy(List<String> initialTools, List<String> repairTools) {

        public ToolPolicy {
            initialTools = immutableTools(initialTools);
            repairTools = immutableTools(repairTools);
        }

        public static ToolPolicy of(Collection<String> initialTools, Collection<String> repairTools) {
            return new ToolPolicy(
                    initialTools == null ? List.of() : List.copyOf(initialTools),
                    repairTools == null ? List.of() : List.copyOf(repairTools));
        }

        private static List<String> immutableTools(Collection<String> tools) {
            return List.copyOf(new LinkedHashSet<>(tools == null ? List.of() : tools));
        }
    }

    private enum ToolPhase {
        INITIAL_AVAILABLE,
        INITIAL_IN_FLIGHT,
        REPAIR
    }

    private record SessionToolPolicy(ToolPolicy toolPolicy, ToolPhase phase) {

        private SessionToolPolicy withPhase(ToolPhase nextPhase) {
            return phase == nextPhase ? this : new SessionToolPolicy(toolPolicy, nextPhase);
        }
    }
}
