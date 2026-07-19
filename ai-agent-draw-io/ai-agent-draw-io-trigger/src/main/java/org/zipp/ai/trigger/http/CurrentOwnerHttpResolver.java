package org.zipp.ai.trigger.http;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.OwnerResolutionCommand;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.account.service.IAccountService;
import org.zipp.ai.domain.account.service.ICurrentOwnerResolver;
import org.zipp.ai.trigger.http.service.AuthenticatedUserPrincipal;
import org.zipp.ai.types.util.SecretLogSanitizer;

import javax.annotation.Resource;
import java.util.Optional;

@Component
public class CurrentOwnerHttpResolver {

    @Resource
    private ICurrentOwnerResolver currentOwnerResolver;

    @Resource
    private IAccountService accountService;

    @Resource
    private AnonymousWorkspaceCookie anonymousWorkspaceCookie;

    public Optional<ResolvedOwner> resolve(String legacyOwnerId) {
        ServletRequestAttributes attributes = currentRequest();
        String authenticatedUserId = currentValidSessionUserId(attributes);
        String anonymousCredential = attributes == null || anonymousWorkspaceCookie == null
                ? null
                : anonymousWorkspaceCookie.read(attributes.getRequest()).orElse(null);
        if (currentOwnerResolver == null) {
            // Keeps direct controller tests honest without creating an anonymous fallback. A real
            // authenticated Spring principal is already authoritative; anonymous access still
            // requires the injected credential-aware resolver.
            return authenticatedUserId == null
                    ? Optional.empty()
                    : Optional.of(ResolvedOwner.authenticated(authenticatedUserId));
        }
        // legacyOwnerId is intentionally ignored: request data may name an owner but cannot prove it.
        return currentOwnerResolver.resolve(OwnerResolutionCommand.builder()
                .anonymousCredential(anonymousCredential)
                .authenticatedUserId(authenticatedUserId)
                .build());
    }

    public Optional<String> resolveOwnerId(String legacyOwnerId) {
        return resolve(legacyOwnerId).map(ResolvedOwner::getOwnerId);
    }

    public static String mask(String ownerId) {
        return SecretLogSanitizer.maskCapability(ownerId);
    }

    private String currentValidSessionUserId(ServletRequestAttributes attributes) {
        String userId = AuthenticatedUserPrincipal.currentUserId();
        if (userId == null) {
            return null;
        }
        if (accountService == null) {
            return userId;
        }
        Integer sessionVersion = AuthenticatedUserPrincipal.currentSessionVersion();
        Optional<UserAccount> user = accountService.findById(userId);
        if (user.isPresent() && user.get().isActive()
                && sessionVersion != null && sessionVersion == user.get().getSessionVersion()) {
            return userId;
        }
        if (attributes != null && attributes.getRequest().getSession(false) != null) {
            attributes.getRequest().getSession(false).invalidate();
        }
        SecurityContextHolder.clearContext();
        return null;
    }

    private ServletRequestAttributes currentRequest() {
        try {
            return (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        } catch (Exception e) {
            return null;
        }
    }
}
