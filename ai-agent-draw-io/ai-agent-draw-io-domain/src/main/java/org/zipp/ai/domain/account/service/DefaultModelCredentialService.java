package org.zipp.ai.domain.account.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.zipp.ai.domain.account.model.entity.ModelCredential;
import org.zipp.ai.domain.account.model.entity.UserAccount;
import org.zipp.ai.domain.account.model.valobj.CreateModelCredentialCommand;
import org.zipp.ai.domain.account.model.valobj.EncryptedModelCredentialSecret;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialStatus;
import org.zipp.ai.domain.account.model.valobj.ModelCredentialSummary;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class DefaultModelCredentialService implements IModelCredentialService {

    private static final int MAX_PROVIDER_LENGTH = 64;
    private static final int MAX_BASE_URL_LENGTH = 512;
    private static final int MAX_MODEL_LENGTH = 128;
    private static final int MAX_COMPLETION_PATH_LENGTH = 255;
    private static final int MAX_DISPLAY_NAME_LENGTH = 128;

    private final IUserAccountStore userAccountStore;
    private final IModelCredentialStore modelCredentialStore;
    private final IModelCredentialSecretCipher secretCipher;
    private final Clock clock;

    @Autowired
    public DefaultModelCredentialService(IUserAccountStore userAccountStore,
                                         IModelCredentialStore modelCredentialStore,
                                         IModelCredentialSecretCipher secretCipher) {
        this(userAccountStore, modelCredentialStore, secretCipher, Clock.systemUTC());
    }

    public DefaultModelCredentialService(IUserAccountStore userAccountStore,
                                         IModelCredentialStore modelCredentialStore,
                                         IModelCredentialSecretCipher secretCipher,
                                         Clock clock) {
        this.userAccountStore = userAccountStore;
        this.modelCredentialStore = modelCredentialStore;
        this.secretCipher = secretCipher;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public ModelCredentialSummary create(CreateModelCredentialCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("credential request is required.");
        }
        String userId = requireVerifiedUser(command == null ? null : command.getUserId());
        String provider = requireText(command.getProvider(), "provider", MAX_PROVIDER_LENGTH);
        String baseUrl = normalizeSafeBaseUrl(command.getBaseUrl());
        String model = requireText(command.getModel(), "model", MAX_MODEL_LENGTH);
        String completionPath = normalizeCompletionPath(command.getCompletionPath());
        String displayName = requireText(command.getDisplayName(), "displayName", MAX_DISPLAY_NAME_LENGTH);
        String apiKey = requireText(command.getApiKey(), "apiKey", 4096);

        EncryptedModelCredentialSecret encrypted = secretCipher.encrypt(apiKey);
        String keyLastFour = lastFour(apiKey);
        Instant now = clock.instant();
        ModelCredential credential = ModelCredential.builder()
                .id("mcr_" + UUID.randomUUID())
                .userId(userId)
                .provider(provider)
                .baseUrl(baseUrl)
                .model(model)
                .completionPath(completionPath)
                .displayName(displayName)
                .encryptedApiKey(encrypted.getCiphertext())
                .encryptionProvider(requireText(encrypted.getEncryptionProvider(), "encryptionProvider", 64))
                .encryptionKeyId(requireText(encrypted.getEncryptionKeyId(), "encryptionKeyId", 128))
                .encryptionNonce(requireText(encrypted.getNonce(), "encryptionNonce", 128))
                .keyLastFour(keyLastFour)
                .status(ModelCredentialStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        modelCredentialStore.insert(credential);
        return toSummary(credential);
    }

    @Override
    public List<ModelCredentialSummary> list(String userId) {
        String verifiedUserId = requireVerifiedUser(userId);
        return modelCredentialStore.listByUserId(verifiedUserId).stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    public boolean disable(String userId, String credentialId) {
        String verifiedUserId = requireVerifiedUser(userId);
        String id = requireText(credentialId, "credentialId", 64);
        return modelCredentialStore.disable(verifiedUserId, id, clock.instant());
    }

    @Override
    public boolean delete(String userId, String credentialId) {
        String verifiedUserId = requireVerifiedUser(userId);
        String id = requireText(credentialId, "credentialId", 64);
        return modelCredentialStore.delete(verifiedUserId, id, clock.instant());
    }

    private String requireVerifiedUser(String userId) {
        String normalized = requireText(userId, "userId", 64);
        Optional<UserAccount> user = userAccountStore.findById(normalized);
        if (user.isEmpty() || !user.get().isActive()) {
            throw new IllegalArgumentException("A verified user session is required.");
        }
        return normalized;
    }

    private ModelCredentialSummary toSummary(ModelCredential credential) {
        return ModelCredentialSummary.builder()
                .id(credential.getId())
                .provider(credential.getProvider())
                .baseUrl(credential.getBaseUrl())
                .model(credential.getModel())
                .completionPath(credential.getCompletionPath())
                .displayName(credential.getDisplayName())
                .maskedApiKey("****" + credential.getKeyLastFour())
                .encryptionProvider(credential.getEncryptionProvider())
                .encryptionKeyId(credential.getEncryptionKeyId())
                .keyLastFour(credential.getKeyLastFour())
                .status(credential.getStatus())
                .createdAt(credential.getCreatedAt())
                .updatedAt(credential.getUpdatedAt())
                .disabledAt(credential.getDisabledAt())
                .build();
    }

    private String normalizeSafeBaseUrl(String rawBaseUrl) {
        String baseUrl = requireText(rawBaseUrl, "baseUrl", MAX_BASE_URL_LENGTH);
        URI parsed;
        try {
            parsed = new URI(baseUrl);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("baseUrl must be a valid HTTPS URL.");
        }
        if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUrl must be an HTTPS URL with a public host.");
        }
        if (parsed.getUserInfo() != null || parsed.getQuery() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not include credentials, query, or fragment.");
        }
        if (hasParentTraversalSegment(parsed.getRawPath())) {
            throw new IllegalArgumentException("baseUrl path must not contain parent traversal.");
        }
        if (isBlockedHost(parsed.getHost())) {
            throw new IllegalArgumentException("baseUrl host is not allowed.");
        }
        return parsed.normalize().toString();
    }

    private String normalizeCompletionPath(String rawCompletionPath) {
        String path = requireText(rawCompletionPath, "completionPath", MAX_COMPLETION_PATH_LENGTH);
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("://")) {
            throw new IllegalArgumentException("completionPath must be an absolute path.");
        }
        if (hasParentTraversalSegment(path)) {
            throw new IllegalArgumentException("completionPath must be a safe path.");
        }
        URI uri;
        try {
            uri = new URI(path).normalize();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("completionPath must be a valid path.");
        }
        if (uri.isAbsolute() || uri.getHost() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPath() == null || uri.getPath().contains("/../") || uri.getPath().equals("/..")) {
            throw new IllegalArgumentException("completionPath must be a safe path.");
        }
        return uri.getPath();
    }

    private boolean isBlockedHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            return true;
        }
        if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal")) {
            return true;
        }
        if (host.startsWith("0x")) {
            return true;
        }
        if (isBlockedIpv4(host)) {
            return true;
        }
        if (isAmbiguousNumericHost(host)) {
            return true;
        }
        return host.contains(":") && isBlockedIpv6(host);
    }

    private boolean isAmbiguousNumericHost(String host) {
        if (!host.matches("[0-9.]+")) {
            return false;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return true;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 1 && part.startsWith("0")) {
                return true;
            }
            try {
                int octet = Integer.parseInt(part);
                if (octet < 0 || octet > 255) {
                    return true;
                }
            } catch (NumberFormatException e) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlockedIpv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > 1 && parts[i].startsWith("0")) {
                return true;
            }
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) {
                return false;
            }
        }
        int first = octets[0];
        int second = octets[1];
        return first == 0
                || first == 10
                || first == 127
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || first >= 224;
    }

    private boolean isBlockedIpv6(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress();
        } catch (Exception e) {
            return true;
        }
    }

    private boolean hasParentTraversalSegment(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        if (hasRawParentTraversalSegment(rawPath)) {
            return true;
        }
        String decodedPath;
        try {
            decodedPath = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return true;
        }
        // Encoded slashes can create new path segments after a downstream client/server decodes them.
        return !decodedPath.equals(rawPath) && hasRawParentTraversalSegment(decodedPath);
    }

    private boolean hasRawParentTraversalSegment(String rawPath) {
        for (String segment : rawPath.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private String requireText(String value, String field, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(field + " is too long.");
        }
        return trimmed;
    }

    private String lastFour(String value) {
        return value.length() <= 4 ? value : value.substring(value.length() - 4);
    }
}
