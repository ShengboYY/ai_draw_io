package org.zipp.ai.ingestion.worker.document;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact processing profiles that one worker can reproduce without changing document output.
 */
public final class DocumentProcessingProfiles {

    private static final String EXECUTABLE_MARKER = ":executable=";
    private static final String LANGUAGES_MARKER = ":languages=";

    private final DocumentProcessingProfile active;
    private final Map<String, DocumentProcessingProfile> byFingerprint;

    private DocumentProcessingProfiles(DocumentProcessingProfile active,
                                       Map<String, DocumentProcessingProfile> byFingerprint) {
        this.active = active;
        this.byFingerprint = Map.copyOf(byFingerprint);
    }

    public static DocumentProcessingProfiles of(DocumentProcessingProfile active) {
        DocumentProcessingProfile profile = Objects.requireNonNull(active, "active");
        return new DocumentProcessingProfiles(
                profile, Map.of(profile.overallFingerprint(), profile));
    }

    public static DocumentProcessingProfiles withLegacyExecutablePath(
            DocumentProcessingProfile active, String configuredExecutable) {
        return withLegacyExecutablePaths(active, Set.of(
                requireText(configuredExecutable, "configuredExecutable")));
    }

    public static DocumentProcessingProfiles withLegacyExecutablePaths(
            DocumentProcessingProfile active, Collection<String> configuredExecutables) {
        DocumentProcessingProfile canonical = Objects.requireNonNull(active, "active");
        Map<String, DocumentProcessingProfile> profiles = new LinkedHashMap<>();
        profiles.put(canonical.overallFingerprint(), canonical);
        for (String configuredExecutable : Objects.requireNonNull(
                configuredExecutables, "configuredExecutables")) {
            String executable = requireText(configuredExecutable, "configuredExecutable");
            // Older releases fingerprinted the configured path; retain only operator-declared aliases.
            DocumentProcessingProfile legacy = withExecutableIdentity(canonical, executable);
            profiles.put(legacy.overallFingerprint(), legacy);
        }
        return new DocumentProcessingProfiles(canonical, profiles);
    }

    public DocumentProcessingProfile active() {
        return active;
    }

    public Set<String> acceptedFingerprints() {
        return byFingerprint.keySet();
    }

    public Optional<DocumentProcessingProfile> find(String fingerprint) {
        return Optional.ofNullable(byFingerprint.get(fingerprint));
    }

    public DocumentProcessingProfile require(String fingerprint) {
        return find(fingerprint).orElseThrow(
                () -> new IllegalArgumentException("processing profile is not compatible"));
    }

    private static DocumentProcessingProfile withExecutableIdentity(
            DocumentProcessingProfile profile, String executable) {
        String ocr = profile.ocr();
        int start = ocr.indexOf(EXECUTABLE_MARKER);
        int end = ocr.indexOf(LANGUAGES_MARKER, start + EXECUTABLE_MARKER.length());
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("OCR profile does not contain an executable identity");
        }
        String legacyOcr = ocr.substring(0, start + EXECUTABLE_MARKER.length())
                + executable + ocr.substring(end);
        return new DocumentProcessingProfile(
                profile.parser(), legacyOcr, profile.selection(), profile.canonical(),
                profile.structure(), profile.visual(), profile.evidence(), profile.retrieval());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
