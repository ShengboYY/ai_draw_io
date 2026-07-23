package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.RunResourceDomain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

/** Reads exact image versions and accepts only bounded, source-anchored model observations. */
public final class DefaultVisualObservationModule implements VisualObservationModule {
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;
    private static final double MIN_CONFIDENCE = 0.60;

    private final VisualArtifactReaderPort artifacts;
    private final VisionModelPort model;
    private final ExecutorService executor;
    private final long timeoutMillis;

    public DefaultVisualObservationModule(VisualArtifactReaderPort artifacts, VisionModelPort model) {
        this(artifacts, model, ForkJoinPool.commonPool(), 30_000);
    }

    public DefaultVisualObservationModule(VisualArtifactReaderPort artifacts, VisionModelPort model,
                                          ExecutorService executor, long timeoutMillis) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.model = Objects.requireNonNull(model, "model");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (timeoutMillis < 1) throw new IllegalArgumentException("timeoutMillis must be positive");
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public java.util.concurrent.CompletionStage<VisualObservationOutcome> observe(
            VisualObservationCommand command, RunResourceDomain resources, CancellationSignal cancellation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resources, "resources");
        CancellationSignal signal = cancellation == null ? CancellationSignal.NEVER : cancellation;
        if (stopped(resources, signal)) {
            return CompletableFuture.completedFuture(new VisualObservationOutcome.Cancelled());
        }

        CompletableFuture<VisualObservationOutcome> result = new CompletableFuture<>();
        Future<?> task = executor.submit(() -> result.complete(observeNow(command, resources, signal)));
        // Closing a run interrupts a visual provider call that is still in flight.
        resources.attach(() -> task.cancel(true));
        CompletableFuture.delayedExecutor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
            if (result.complete(new VisualObservationOutcome.Unavailable("VISUAL_PROVIDER_UNAVAILABLE"))) {
                task.cancel(true);
            }
        });
        return result;
    }

    private VisualObservationOutcome observeNow(VisualObservationCommand command, RunResourceDomain resources,
                                                CancellationSignal signal) {
        try {
            List<VisionModelPort.ImageInput> images = new ArrayList<>();
            long totalBytes = 0;
            for (VisualObservationTarget target : command.targets()) {
                if (stopped(resources, signal)) return new VisualObservationOutcome.Cancelled();
                if (target.artifact().byteSize() > MAX_IMAGE_BYTES
                        || totalBytes + target.artifact().byteSize() > MAX_TOTAL_BYTES) {
                    return new VisualObservationOutcome.Rejected("VISUAL_PIXEL_BUDGET_EXCEEDED");
                }
                byte[] bytes = artifacts.read(target.artifact(), MAX_IMAGE_BYTES);
                totalBytes += bytes.length;
                images.add(new VisionModelPort.ImageInput(
                        target.evidenceId(), target.artifact().contentType(), bytes));
                if (stopped(resources, signal)) return new VisualObservationOutcome.Cancelled();
            }
            if (stopped(resources, signal)) return new VisualObservationOutcome.Cancelled();
            VisionModelPort.Response response = model.observe(new VisionModelPort.Request(
                    command.purpose(), command.question(), images, command.maximumObservations()));
            if (stopped(resources, signal)) return new VisualObservationOutcome.Cancelled();
            Set<String> allowedEvidence = new HashSet<>(
                    command.targets().stream().map(VisualObservationTarget::evidenceId).toList());
            List<VerifiedObservation> verified = response.observations().stream()
                    .filter(observation -> allowedEvidence.contains(observation.evidenceId()))
                    .filter(observation -> observation.confidence() >= MIN_CONFIDENCE)
                    .limit(command.maximumObservations()).toList();
            if (!response.gaps().isEmpty() || verified.size() != response.observations().size()) {
                List<String> gaps = response.gaps().isEmpty()
                        ? List.of("LOW_CONFIDENCE_OR_INVALID_ANCHOR") : response.gaps();
                return new VisualObservationOutcome.Gap(gaps);
            }
            return verified.isEmpty()
                    ? new VisualObservationOutcome.Gap(List.of("NO_VERIFIED_OBSERVATION"))
                    : new VisualObservationOutcome.Verified(verified);
        } catch (IllegalArgumentException exception) {
            return new VisualObservationOutcome.Rejected("INVALID_VISUAL_INPUT");
        } catch (RuntimeException exception) {
            if (stopped(resources, signal)) return new VisualObservationOutcome.Cancelled();
            return new VisualObservationOutcome.Unavailable("VISUAL_PROVIDER_UNAVAILABLE");
        }
    }

    private boolean stopped(RunResourceDomain resources, CancellationSignal signal) {
        return resources.isClosed() || signal.isCancelled() || Thread.currentThread().isInterrupted();
    }
}
