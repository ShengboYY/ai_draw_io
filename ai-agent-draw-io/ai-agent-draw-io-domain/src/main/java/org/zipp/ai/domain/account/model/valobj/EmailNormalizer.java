package org.zipp.ai.domain.account.model.valobj;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizes and validates email addresses. Normalization (trim + lowercase) happens before every
 * uniqueness check so the same address cannot register twice with different casing/whitespace.
 */
public final class EmailNormalizer {

    // Deliberately permissive: reject obvious garbage without trying to fully validate RFC 5322.
    private static final Pattern BASIC_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private EmailNormalizer() {
    }

    public static boolean isValid(String email) {
        if (email == null) {
            return false;
        }
        return BASIC_EMAIL.matcher(email.trim()).matches();
    }

    /** Returns the trimmed, lowercased email, or {@code null} when the input is not a valid email. */
    public static String normalize(String email) {
        if (!isValid(email)) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
