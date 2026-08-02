package org.zipp.ai.application.memory;

import org.zipp.ai.domain.retrieval.port.RetryableRetrievalException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Synchronizes one fenced Memory snapshot without making Pinecone an authority. */
public final class AutoMemoryVectorProjectionWorker {
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    private final AutoMemoryVectorProjectionWorkPort work;
    private final AutoMemoryVectorStorePort vectors;
    private final Clock clock;

    public AutoMemoryVectorProjectionWorker(
            AutoMemoryVectorProjectionWorkPort work,
            AutoMemoryVectorStorePort vectors,
            Clock clock
    ) {
        this.work = Objects.requireNonNull(work, "work");
        this.vectors = Objects.requireNonNull(vectors, "vectors");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean runOnce(String workerId) {
        Instant now = clock.instant();
        var claimed = work.claim(workerId, now, LEASE_DURATION);
        if (claimed.isEmpty()) {
            return false;
        }
        AutoMemoryVectorProjectionLease lease = claimed.get();
        try {
            project(lease);
            if (!work.complete(lease, clock.instant())) {
                // A lease can expire while provider I/O is still running. Reconcile once more so
                // a late stale upsert cannot become the vector store's final visible value.
                work.enqueue(lease.memoryId());
            }
        } catch (RuntimeException failure) {
            Instant failedAt = clock.instant();
            boolean retryScheduled = work.retry(
                    lease,
                    safeErrorCode(failure),
                    failedAt.plus(retryDelay(lease.attemptCount(), failure)),
                    failedAt);
            if (!retryScheduled) {
                // Partial provider writes from an expired lease need the same convergence path.
                work.enqueue(lease.memoryId());
            }
        }
        return true;
    }

    private void project(AutoMemoryVectorProjectionLease lease) {
        List<AutoMemoryVectorDocument> documents = lease.documents();
        Set<String> desiredVectorIds = lease.desiredVectorIds();
        if (!documents.isEmpty()) {
            List<float[]> embeddings = vectors.embedPassages(documents.stream()
                    .map(AutoMemoryVectorDocument::retrievalText)
                    .toList());
            if (embeddings == null || embeddings.size() != documents.size()) {
                throw new IllegalStateException("AUTO_MEMORY_EMBEDDING_COUNT_INVALID");
            }
            List<AutoMemoryVector> desired = new ArrayList<>(documents.size());
            for (int index = 0; index < documents.size(); index++) {
                desired.add(new AutoMemoryVector(documents.get(index), embeddings.get(index)));
            }
            vectors.upsert(desired);
            Set<String> visible = vectors.existingVectorIds(
                    documents.stream().map(AutoMemoryVectorDocument::vectorId).toList());
            if (!visible.containsAll(desiredVectorIds)) {
                throw new RetryableRetrievalException(
                        "Memory vector projection is not visible yet", Duration.ofSeconds(10));
            }
            Set<String> searchable = vectors.searchableVectorIds(desired);
            if (!searchable.containsAll(desiredVectorIds)) {
                // Pinecone fetch can become visible before its ANN index accepts the same vector.
                throw new RetryableRetrievalException(
                        "Memory vector projection is not searchable yet", Duration.ofSeconds(10));
            }
        }

        List<String> stale = lease.projectedVectorIds().stream()
                .filter(vectorId -> !desiredVectorIds.contains(vectorId))
                .sorted()
                .toList();
        vectors.delete(stale);
    }

    private static Duration retryDelay(int attemptCount, RuntimeException failure) {
        if (failure instanceof RetryableRetrievalException retryable
                && retryable.retryAfter() != null
                && !retryable.retryAfter().isNegative()
                && !retryable.retryAfter().isZero()) {
            return retryable.retryAfter().compareTo(MAX_RETRY_DELAY) > 0
                    ? MAX_RETRY_DELAY : retryable.retryAfter();
        }
        long seconds = Math.min(
                MAX_RETRY_DELAY.toSeconds(),
                15L * (1L << Math.min(8, Math.max(0, attemptCount - 1))));
        return Duration.ofSeconds(seconds);
    }

    private static String safeErrorCode(RuntimeException failure) {
        String name = failure.getClass().getSimpleName()
                .replaceAll("[^A-Za-z0-9_]", "")
                .toUpperCase(Locale.ROOT);
        return name.isBlank() ? "AUTO_MEMORY_VECTOR_FAILED"
                : "AUTO_MEMORY_VECTOR_" + name.substring(0, Math.min(name.length(), 41));
    }
}
