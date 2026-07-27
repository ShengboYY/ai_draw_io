package org.zipp.ai.domain.account.model.valobj;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Browser credential split into a non-secret lookup id and a 256-bit random secret.
 * The serialized value may leave the domain only to be placed in the HttpOnly cookie.
 */
public final class AnonymousWorkspaceCredential {

    private static final Pattern CREDENTIAL_ID = Pattern.compile(
            "^awc_[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final int MINIMUM_SECRET_CHARACTERS = 32;

    private final String credentialId;
    private final String secret;

    private AnonymousWorkspaceCredential(String credentialId, String secret) {
        this.credentialId = credentialId;
        this.secret = secret;
    }

    public static AnonymousWorkspaceCredential issue(String credentialId, String secret) {
        if (!isValidCredentialId(credentialId) || secret == null || secret.length() < MINIMUM_SECRET_CHARACTERS) {
            throw new IllegalArgumentException("Anonymous workspace credential is not strong enough");
        }
        return new AnonymousWorkspaceCredential(credentialId, secret);
    }

    public static Optional<AnonymousWorkspaceCredential> parse(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return Optional.empty();
        }
        int separator = serialized.indexOf('.');
        if (separator <= 0 || separator != serialized.lastIndexOf('.')) {
            return Optional.empty();
        }
        String credentialId = serialized.substring(0, separator);
        String secret = serialized.substring(separator + 1);
        if (!isValidCredentialId(credentialId) || secret.length() < MINIMUM_SECRET_CHARACTERS) {
            return Optional.empty();
        }
        return Optional.of(new AnonymousWorkspaceCredential(credentialId, secret));
    }

    private static boolean isValidCredentialId(String credentialId) {
        return credentialId != null && CREDENTIAL_ID.matcher(credentialId).matches();
    }

    public String serialize() {
        return credentialId + "." + secret;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public String getSecret() {
        return secret;
    }
}
