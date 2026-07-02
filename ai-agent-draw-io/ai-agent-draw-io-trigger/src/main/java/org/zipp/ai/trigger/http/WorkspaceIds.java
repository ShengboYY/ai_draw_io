package org.zipp.ai.trigger.http;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.zipp.ai.types.util.SecretLogSanitizer;

import java.util.Locale;
import java.util.regex.Pattern;

final class WorkspaceIds {

    static final String HEADER = "X-Workspace-Id";
    private static final Pattern ANONYMOUS_WORKSPACE_ID = Pattern.compile(
            "^anon_[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private WorkspaceIds() {
    }

    static String resolve(String legacyWorkspaceId) {
        ServletRequestAttributes attributes = currentRequest();
        String candidate = attributes == null
                ? legacyWorkspaceId
                : attributes.getRequest().getHeader(HEADER);
        return normalize(candidate);
    }

    static String mask(String workspaceId) {
        return SecretLogSanitizer.maskCapability(workspaceId);
    }

    private static String normalize(String workspaceId) {
        if (StringUtils.isBlank(workspaceId)) {
            return null;
        }
        String value = workspaceId.trim().toLowerCase(Locale.ROOT);
        // Until real authentication exists, only anonymous capability ids are valid owners.
        return ANONYMOUS_WORKSPACE_ID.matcher(value).matches() ? value : null;
    }

    private static ServletRequestAttributes currentRequest() {
        try {
            return (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        } catch (Exception e) {
            return null;
        }
    }
}
