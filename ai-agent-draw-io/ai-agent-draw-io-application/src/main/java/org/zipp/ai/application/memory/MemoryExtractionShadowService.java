package org.zipp.ai.application.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Evaluates automatic Memory candidates without entering the v1 Memory write path. The service
 * sanitizes, groups by decision scope, reports duplicates/conflicts, and returns only an in-memory
 * shadow report. It intentionally has no MemoryCandidateStorePort dependency.
 */
public final class MemoryExtractionShadowService {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private final MemoryPolicySanitizer sanitizer;
    private final Clock clock;
    private final Duration maximumEventAge;

    public MemoryExtractionShadowService() {
        this(new MemoryPolicySanitizer(), Clock.systemUTC(), Duration.ofHours(24));
    }

    public MemoryExtractionShadowService(
            MemoryPolicySanitizer sanitizer, Clock clock, Duration maximumEventAge) {
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumEventAge = Objects.requireNonNull(maximumEventAge, "maximumEventAge");
        if (maximumEventAge.isNegative() || maximumEventAge.isZero()) {
            throw new IllegalArgumentException("maximumEventAge must be positive");
        }
    }

    public MemoryShadowReport observe(MemoryShadowTurnCommitted event) {
        Objects.requireNonNull(event, "event");
        Instant now = clock.instant();
        if (event.committedAt().isAfter(now)
                || event.committedAt().isBefore(now.minus(maximumEventAge))) {
            return staleReport(event);
        }

        List<MemoryShadowObservation> observations = new ArrayList<>();
        Map<ShadowGroupKey, List<PreparedCandidate>> groups = new LinkedHashMap<>();
        int rejected = 0;
        for (MemoryShadowCandidate candidate : event.candidates()) {
            CandidatePreparation preparation = prepare(candidate);
            if (preparation instanceof CandidatePreparation.Rejected rejectedCandidate) {
                observations.add(new MemoryShadowObservation(
                        candidate.candidateRef(), MemoryShadowCandidateStatus.REJECTED,
                        null, null, null, rejectedCandidate.code()));
                rejected++;
                continue;
            }
            PreparedCandidate accepted = ((CandidatePreparation.Accepted) preparation).candidate();
            groups.computeIfAbsent(accepted.groupKey(), ignored -> new ArrayList<>()).add(accepted);
        }

        int acceptedCount = 0;
        int duplicateCount = 0;
        int conflictCount = 0;
        for (List<PreparedCandidate> group : groups.values()) {
            Map<String, List<PreparedCandidate>> byText = new LinkedHashMap<>();
            for (PreparedCandidate candidate : group) {
                byText.computeIfAbsent(candidate.normalizedText(), ignored -> new ArrayList<>())
                        .add(candidate);
            }
            if (byText.size() > 1) {
                for (PreparedCandidate candidate : group) {
                    observations.add(observation(candidate, MemoryShadowCandidateStatus.CONFLICT, null));
                    conflictCount++;
                }
                continue;
            }
            boolean first = true;
            for (PreparedCandidate candidate : group) {
                if (first) {
                    observations.add(observation(candidate, MemoryShadowCandidateStatus.SHADOW_ACCEPTED,
                            candidate.sanitizedText()));
                    acceptedCount++;
                    first = false;
                } else {
                    observations.add(observation(candidate, MemoryShadowCandidateStatus.DUPLICATE, null));
                    duplicateCount++;
                }
            }
        }
        return new MemoryShadowReport(event.turn(), event.chartbookId(), observations,
                new MemoryShadowMetrics(acceptedCount, duplicateCount, conflictCount, rejected, 0));
    }

    private CandidatePreparation prepare(MemoryShadowCandidate candidate) {
        String decisionKey = normalize(candidate.decisionKey());
        String stage = normalize(candidate.applicabilityStage());
        if (!KEY.matcher(decisionKey).matches() || sanitizer.isProfileOwnedField(decisionKey)
                || stage.length() > 64
                || !KEY.matcher(stage.replace(' ', '-')).matches()) {
            return new CandidatePreparation.Rejected(sanitizer.isProfileOwnedField(decisionKey)
                    ? "MEMORY_PROFILE_FIELD_FORBIDDEN" : "MEMORY_SHADOW_SCOPE_INVALID");
        }
        MemoryPolicySanitizer.TextSanitizationOutcome text = sanitizer.sanitizeShadowText(
                candidate.canonicalText());
        if (text instanceof MemoryPolicySanitizer.TextSanitizationOutcome.Rejected rejected) {
            return new CandidatePreparation.Rejected(rejected.code());
        }
        String safeText = ((MemoryPolicySanitizer.TextSanitizationOutcome.Accepted) text).text();
        return new CandidatePreparation.Accepted(new PreparedCandidate(
                candidate.candidateRef(), decisionKey, stage, safeText,
                normalize(safeText), new ShadowGroupKey(decisionKey, stage)));
    }

    private static MemoryShadowObservation observation(
            PreparedCandidate candidate, MemoryShadowCandidateStatus status, String text) {
        return new MemoryShadowObservation(candidate.candidateRef(), status,
                candidate.decisionKey(), candidate.applicabilityStage(), text, null);
    }

    private static MemoryShadowReport staleReport(MemoryShadowTurnCommitted event) {
        List<MemoryShadowObservation> observations = event.candidates().stream()
                .map(candidate -> new MemoryShadowObservation(
                        candidate.candidateRef(), MemoryShadowCandidateStatus.REJECTED,
                        null, null, null, "MEMORY_SHADOW_STALE_EVENT"))
                .toList();
        return new MemoryShadowReport(event.turn(), event.chartbookId(), observations,
                new MemoryShadowMetrics(0, 0, 0, observations.size(), 1));
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private sealed interface CandidatePreparation {
        record Accepted(PreparedCandidate candidate) implements CandidatePreparation {
        }

        record Rejected(String code) implements CandidatePreparation {
        }
    }

    private record PreparedCandidate(
            String candidateRef,
            String decisionKey,
            String applicabilityStage,
            String sanitizedText,
            String normalizedText,
            ShadowGroupKey groupKey
    ) {
    }

    private record ShadowGroupKey(String decisionKey, String applicabilityStage) {
    }
}
