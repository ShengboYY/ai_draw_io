package org.zipp.ai.application.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Filters extractor output before delegating one atomic observation to persistence. */
public final class AutoMemoryObservationService {
    public static final String POLICY_VERSION = "AUTO_MEMORY_V1";
    private static final Pattern SEMANTIC_KEY =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final int MAX_TITLE_LENGTH = 160;

    private final MemoryPolicySanitizer sanitizer;
    private final AutoMemoryActivationPolicy activationPolicy;
    private final AutoMemoryObservationStorePort store;
    private final Clock clock;

    public AutoMemoryObservationService(AutoMemoryObservationStorePort store) {
        this(new MemoryPolicySanitizer(), new AutoMemoryActivationPolicy(), store, Clock.systemUTC());
    }

    public AutoMemoryObservationService(
            MemoryPolicySanitizer sanitizer,
            AutoMemoryActivationPolicy activationPolicy,
            AutoMemoryObservationStorePort store,
            Clock clock
    ) {
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.activationPolicy = Objects.requireNonNull(activationPolicy, "activationPolicy");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AutoMemoryObservationOutcome observe(AutoMemoryObservationCommand command) {
        if (command == null) {
            return new AutoMemoryObservationOutcome.Rejected("AUTO_MEMORY_COMMAND_INVALID");
        }
        String semanticKey = normalizeKey(command.semanticKey());
        if (!SEMANTIC_KEY.matcher(semanticKey).matches()) {
            return new AutoMemoryObservationOutcome.Rejected("AUTO_MEMORY_SEMANTIC_KEY_INVALID");
        }
        if (sanitizer.isProfileOwnedField(semanticKey)) {
            return new AutoMemoryObservationOutcome.Rejected("MEMORY_PROFILE_FIELD_FORBIDDEN");
        }
        String title = normalizeText(command.title());
        if (title.isBlank() || title.length() > MAX_TITLE_LENGTH) {
            return new AutoMemoryObservationOutcome.Rejected("AUTO_MEMORY_TITLE_INVALID");
        }
        MemoryPolicySanitizer.TextSanitizationOutcome titleOutcome =
                sanitizer.sanitizeText(title);
        if (titleOutcome instanceof MemoryPolicySanitizer.TextSanitizationOutcome.Rejected rejected) {
            return new AutoMemoryObservationOutcome.Rejected(rejected.code());
        }
        MemoryPolicySanitizer.TextSanitizationOutcome textOutcome =
                sanitizer.sanitizeText(command.canonicalText());
        if (textOutcome instanceof MemoryPolicySanitizer.TextSanitizationOutcome.Rejected rejected) {
            return new AutoMemoryObservationOutcome.Rejected(rejected.code());
        }
        String safeTitle =
                ((MemoryPolicySanitizer.TextSanitizationOutcome.Accepted) titleOutcome).text();
        String safeText =
                ((MemoryPolicySanitizer.TextSanitizationOutcome.Accepted) textOutcome).text();
        double confidence = command.observationKind().isExplicit()
                ? 1.0d : command.confidence();
        SanitizedAutoMemoryObservation observation = new SanitizedAutoMemoryObservation(
                command.scope(), command.type(), semanticKey, safeTitle, safeText,
                command.sourceTurn(), command.sourceDiagramId(), command.observationKind(),
                confidence, POLICY_VERSION,
                digest(command.scope(), command.type(), semanticKey, safeText,
                        command.sourceTurn().canonicalConversationId(), command.sourceTurn().turnId()),
                clock.instant());
        return store.observe(observation, activationPolicy);
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "-");
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String digest(
            AutoMemoryScope scope,
            AutoMemoryType type,
            String semanticKey,
            String text,
            String conversationId,
            String turnId
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, scope.ownerKey());
            add(digest, scope.type().name());
            add(digest, scope.scopeKey());
            add(digest, type.name());
            add(digest, semanticKey);
            add(digest, text);
            add(digest, conversationId);
            add(digest, turnId);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '|');
    }
}
