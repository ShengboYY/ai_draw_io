package org.zipp.ai.application.memory;

import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.ExplicitMemoryDecision;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Claims one durable Turn, extracts bounded drafts and feeds them through the same observation
 * policy as explicit Memory. One work item is deliberately small to keep scheduler runs bounded.
 */
public final class AutoMemoryExtractionWorker {
    private static final int MAX_DRAFTS = 4;
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    private final AutoMemoryExtractionWorkPort work;
    private final AutoMemoryExtractionPort extractor;
    private final AutoMemoryObservationService observations;
    private final Clock clock;

    public AutoMemoryExtractionWorker(
            AutoMemoryExtractionWorkPort work,
            AutoMemoryExtractionPort extractor,
            AutoMemoryObservationService observations,
            Clock clock
    ) {
        this.work = Objects.requireNonNull(work, "work");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean runOnce(String workerId) {
        Instant now = clock.instant();
        var claimed = work.claim(workerId, now, LEASE_DURATION);
        if (claimed.isEmpty()) {
            return false;
        }
        AutoMemoryExtractionLease lease = claimed.get();
        try {
            Optional<String> explicitText = explicitText(lease);
            if (explicitText.isPresent()) {
                observeExplicit(lease, explicitText.get());
            } else {
                observeInferred(lease);
            }
            if (!work.complete(lease, clock.instant())) {
                throw new IllegalStateException("AUTO_MEMORY_WORK_FENCE_LOST");
            }
        } catch (RuntimeException failure) {
            work.retry(
                    lease,
                    safeErrorCode(failure),
                    clock.instant().plus(retryDelay(lease.attemptCount())),
                    clock.instant());
        }
        return true;
    }

    private Optional<String> explicitText(AutoMemoryExtractionLease lease) {
        if (lease.hasExplicitObservation()) {
            return Optional.of(lease.explicitCanonicalText());
        }
        if (!ExplicitMemoryDecision.targetsUserScope(lease.userContent())) {
            return Optional.empty();
        }
        // A USER-scoped instruction does not require a current Chartbook. Re-run the same
        // high-precision parser over the committed user message before granting explicit status.
        return ExplicitMemoryDecision.canonicalTextFromExplicitUserContent(lease.userContent());
    }

    private void observeExplicit(AutoMemoryExtractionLease lease, String text) {
        // Existing locale rules prove explicit user intent. They bypass model inference and become
        // active immediately; the stable content digest makes retries idempotent.
        boolean userScope = ExplicitMemoryDecision.targetsUserScope(lease.userContent());
        if (!userScope && lease.chartbookId() == null) {
            // The v1 declaration was Chartbook-scoped. Deletion/archival must not widen it to USER.
            return;
        }
        AutoMemoryScope scope = userScope
                ? AutoMemoryScope.user(lease.turn().ownerKey())
                : AutoMemoryScope.chartbook(lease.turn().ownerKey(), lease.chartbookId());
        observations.observe(new AutoMemoryObservationCommand(
                scope,
                explicitType(text),
                "explicit." + ModelInputBinding.digestOf(normalize(text)).substring(0, 24),
                "Explicit user memory",
                text,
                lease.turn(),
                lease.diagramId(),
                MemoryObservationKind.EXPLICIT,
                1.0d));
    }

    private void observeInferred(AutoMemoryExtractionLease lease) {
        String contextDigest = ModelInputBinding.digestOf(
                "auto-memory", lease.diagramId(), lease.chartbookId());
        String inputDigest = ModelInputBinding.digestOf(
                lease.userContent(), contextDigest);
        AutoMemoryExtractionInput input = new AutoMemoryExtractionInput(
                lease.turn(),
                lease.diagramId(),
                lease.chartbookId(),
                lease.userContent(),
                ModelInputBinding.bound(lease.turn(), contextDigest, inputDigest));
        List<AutoMemoryExtractionDraft> drafts = extractor.extract(input);
        if (drafts == null || drafts.size() > MAX_DRAFTS) {
            throw new IllegalStateException("AUTO_MEMORY_EXTRACTOR_OUTPUT_INVALID");
        }
        for (AutoMemoryExtractionDraft draft : drafts) {
            if (draft == null
                    || (draft.scopeType() == MemoryScopeType.CHARTBOOK && !input.hasChartbook())) {
                continue;
            }
            AutoMemoryScope scope = draft.scopeType() == MemoryScopeType.USER
                    ? AutoMemoryScope.user(lease.turn().ownerKey())
                    : AutoMemoryScope.chartbook(lease.turn().ownerKey(), lease.chartbookId());
            observations.observe(new AutoMemoryObservationCommand(
                    scope,
                    draft.type(),
                    draft.semanticKey(),
                    draft.title(),
                    draft.canonicalText(),
                    lease.turn(),
                    lease.diagramId(),
                    MemoryObservationKind.INFERRED,
                    draft.confidence()));
        }
    }

    private static AutoMemoryType explicitType(String text) {
        String normalized = normalize(text);
        if (normalized.contains("prefer") || normalized.contains("偏好")
                || normalized.contains("喜欢") || normalized.contains("喜歡")) {
            return AutoMemoryType.PREFERENCE;
        }
        if (normalized.contains("avoid") || normalized.contains("don't")
                || normalized.contains("不要") || normalized.contains("避免")) {
            return AutoMemoryType.FEEDBACK;
        }
        return AutoMemoryType.PROJECT;
    }

    private static Duration retryDelay(int attemptCount) {
        long seconds = Math.min(
                MAX_RETRY_DELAY.toSeconds(),
                15L * (1L << Math.min(8, Math.max(0, attemptCount - 1))));
        return Duration.ofSeconds(seconds);
    }

    private static String safeErrorCode(RuntimeException failure) {
        String name = failure.getClass().getSimpleName()
                .replaceAll("[^A-Za-z0-9_]", "")
                .toUpperCase(Locale.ROOT);
        return name.isBlank() ? "AUTO_MEMORY_EXTRACTION_FAILED"
                : "AUTO_MEMORY_" + name.substring(0, Math.min(name.length(), 48));
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
