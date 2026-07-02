package org.zipp.ai.trigger.http;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.zipp.ai.types.util.SecretLogSanitizer;

import java.util.Locale;
import java.util.regex.Pattern;

public final class WorkspaceIds {

    public static final String HEADER = "X-Workspace-Id";
    private static final Logger log = LoggerFactory.getLogger(WorkspaceIds.class);
    private static final Pattern ANONYMOUS_WORKSPACE_ID = Pattern.compile(
            "^anon_[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private WorkspaceIds() {
    }

    static String resolve(String legacyWorkspaceId) {
        ServletRequestAttributes attributes = currentRequest();
        if (attributes == null) {
            return normalize(legacyWorkspaceId);
        }
        String workspaceId = attributes.getRequest().getHeader(HEADER);
        if (StringUtils.isBlank(workspaceId) && StringUtils.isNotBlank(legacyWorkspaceId)) {
            // Keep legacy values out of logs; this only flags clients that still need header migration.
            log.warn("Workspace id header missing; ignoring legacy userId parameter. Add {} header to migrate old clients.", HEADER);
        }
        return normalize(workspaceId);
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
