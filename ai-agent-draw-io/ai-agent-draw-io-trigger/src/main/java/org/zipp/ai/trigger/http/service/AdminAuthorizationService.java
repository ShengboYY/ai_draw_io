package org.zipp.ai.trigger.http.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.EmailNormalizer;
import org.zipp.ai.domain.account.service.IAccountService;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AdminAuthorizationService {

    @Resource
    private IAccountService accountService;

    @Value("${admin.emails:}")
    private String adminEmails;

    @Value("${admin.allowed-origins:}")
    private String adminAllowedOrigins;

    public Optional<UserAccount> currentAdmin(HttpServletRequest request) {
        if (!isSameOriginOrAllowed(request)) {
            return Optional.empty();
        }
        String userId = AuthenticatedUserPrincipal.currentUserId();
        Integer sessionVersion = AuthenticatedUserPrincipal.currentSessionVersion();
        if (StringUtils.isBlank(userId) || sessionVersion == null || accountService == null) {
            return Optional.empty();
        }
        Optional<UserAccount> user = accountService.findById(userId);
        if (user.isEmpty() || !user.get().isActive() || sessionVersion != user.get().getSessionVersion()) {
            clearStaleSession(request);
            return Optional.empty();
        }
        return isConfiguredAdmin(user.get()) ? user : Optional.empty();
    }

    private boolean isConfiguredAdmin(UserAccount user) {
        Set<String> allowed = configuredAdminEmails();
        if (allowed.isEmpty()) {
            return false;
        }
        String normalized = StringUtils.defaultIfBlank(user.getEmailNormalized(), EmailNormalizer.normalize(user.getEmail()));
        return normalized != null && allowed.contains(normalized);
    }

    private Set<String> configuredAdminEmails() {
        return Arrays.stream(StringUtils.defaultString(adminEmails).split("[,;\\s]+"))
                .map(EmailNormalizer::normalize)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
    }

    private boolean isSameOriginOrAllowed(HttpServletRequest request) {
        String origin = normalizeOrigin(request == null ? null : request.getHeader("Origin"));
        if (origin == null) {
            origin = normalizeOrigin(request == null ? null : request.getHeader("Referer"));
        }
        if (origin == null) {
            return true;
        }
        return origin.equals(requestOrigin(request)) || configuredAllowedOrigins().contains(origin);
    }

    private Set<String> configuredAllowedOrigins() {
        return Arrays.stream(StringUtils.defaultString(adminAllowedOrigins).split("[,;\\s]+"))
                .map(this::normalizeOrigin)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toSet());
    }

    private String requestOrigin(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        int port = request.getServerPort();
        String scheme = StringUtils.defaultIfBlank(request.getScheme(), "http").toLowerCase();
        String host = StringUtils.defaultIfBlank(request.getServerName(), "").toLowerCase();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port);
    }

    private String normalizeOrigin(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        int pathStart = trimmed.indexOf('/', trimmed.indexOf("://") + 3);
        String origin = pathStart < 0 ? trimmed : trimmed.substring(0, pathStart);
        return origin.toLowerCase();
    }

    private void clearStaleSession(HttpServletRequest request) {
        HttpSession session = request == null ? null : request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
