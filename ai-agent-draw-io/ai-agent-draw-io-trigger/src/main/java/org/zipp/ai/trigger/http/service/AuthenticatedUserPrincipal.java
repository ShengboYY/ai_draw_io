package org.zipp.ai.trigger.http.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Utility for pulling the logged-in user id off the current Spring Security context. Kept as a
 * tiny helper so controllers do not each pluck the principal manually — that would spread the
 * assumption about principal shape across the codebase.
 */
public final class AuthenticatedUserPrincipal {

    private AuthenticatedUserPrincipal() {}

    /** @return session user id, or {@code null} when the caller is anonymous. */
    public static String currentUserId() {
        Object principal = currentPrincipal();
        if (principal instanceof AuthenticatedSessionUser sessionUser) {
            return blankToNull(sessionUser.getUserId());
        }
        if (principal == null) {
            return null;
        }
        String value = principal.toString();
        return value.isBlank() || "anonymousUser".equals(value) ? null : value;
    }

    /** @return session_version captured at login, or {@code null} for legacy string principals. */
    public static Integer currentSessionVersion() {
        Object principal = currentPrincipal();
        return principal instanceof AuthenticatedSessionUser sessionUser
                ? sessionUser.getSessionVersion()
                : null;
    }

    private static Object currentPrincipal() {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context == null) {
            return null;
        }
        Authentication authentication = context.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal == null) {
            return null;
        }
        return principal;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "anonymousUser".equals(value) ? null : value;
    }
}
