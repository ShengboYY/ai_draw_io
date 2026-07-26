package org.zipp.ai.application.turn.planning;

/** Factual role-level failures; the planner decides whether any value is fallback-eligible. */
public enum RoleUnavailability {
    NO_MATCH,
    PROCESSING,
    DEPENDENCY_UNAVAILABLE,
    AUTHORIZATION_VIOLATION,
    REQUIRED_CONFLICT
}
