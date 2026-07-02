package org.zipp.ai.trigger.http;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.zipp.ai.domain.account.model.valobj.OwnerResolutionCommand;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.account.service.DefaultCurrentOwnerResolver;
import org.zipp.ai.domain.account.service.ICurrentOwnerResolver;
import org.zipp.ai.trigger.http.service.AuthenticatedUserPrincipal;
import org.zipp.ai.types.util.SecretLogSanitizer;

import javax.annotation.Resource;
import java.util.Optional;

@Component
public class CurrentOwnerHttpResolver {

    public static final String WORKSPACE_HEADER = "X-Workspace-Id";
    private static final Logger log = LoggerFactory.getLogger(CurrentOwnerHttpResolver.class);

    @Resource
    private ICurrentOwnerResolver currentOwnerResolver;

    public Optional<ResolvedOwner> resolve(String legacyOwnerId) {
        ServletRequestAttributes attributes = currentRequest();
        String workspaceId = attributes == null ? legacyOwnerId : attributes.getRequest().getHeader(WORKSPACE_HEADER);
        String authenticatedUserId = AuthenticatedUserPrincipal.currentUserId();
        // A live session identity supersedes the header — the migration warning is only relevant to
        // anonymous callers who still send a legacy body ownerId.
        if (attributes != null && authenticatedUserId == null
                && StringUtils.isBlank(workspaceId) && StringUtils.isNotBlank(legacyOwnerId)) {
            // Keep legacy values out of logs; this only flags clients that still need header migration.
            log.warn("Workspace id header missing; ignoring legacy userId parameter. Add {} header to migrate old clients.",
                    WORKSPACE_HEADER);
        }
        return resolver().resolve(OwnerResolutionCommand.builder()
                .workspaceId(workspaceId)
                .authenticatedUserId(authenticatedUserId)
                .build());
    }

    public Optional<String> resolveOwnerId(String legacyOwnerId) {
        return resolve(legacyOwnerId).map(ResolvedOwner::getOwnerId);
    }

    public static String mask(String ownerId) {
        return SecretLogSanitizer.maskCapability(ownerId);
    }

    private ICurrentOwnerResolver resolver() {
        return currentOwnerResolver == null ? new DefaultCurrentOwnerResolver() : currentOwnerResolver;
    }

    private ServletRequestAttributes currentRequest() {
        try {
            return (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        } catch (Exception e) {
            return null;
        }
    }
}
