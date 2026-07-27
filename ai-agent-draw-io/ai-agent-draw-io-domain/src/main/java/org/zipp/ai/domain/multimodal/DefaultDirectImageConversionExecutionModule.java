package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationAuthorization;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationCommand;
import org.zipp.ai.domain.agent.model.valobj.canvas.CanvasMutationPurpose;
import org.zipp.ai.domain.grounding.CanvasCommitCommand;
import org.zipp.ai.domain.grounding.CanvasCommitModule;
import org.zipp.ai.domain.grounding.CanvasCommitResult;
import org.zipp.ai.domain.grounding.port.GroundedRunControlPort;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.CloseReason;
import org.zipp.ai.domain.retrieval.EvidenceProgressListener;
import org.zipp.ai.domain.retrieval.RunResourceDomain;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Owns the direct preparation and atomic canvas/citation commit lifecycle. */
public final class DefaultDirectImageConversionExecutionModule
        implements DirectImageConversionExecutionModule {
    private final DirectSourcePreparationModule preparation;
    private final CanvasCommitModule commits;
    private final GroundedRunControlPort runs;

    public DefaultDirectImageConversionExecutionModule(DirectSourcePreparationModule preparation,
                                                       CanvasCommitModule commits,
                                                       GroundedRunControlPort runs) {
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    @Override
    public CompletionStage<DirectImageConversionOutcome> execute(
            DirectImageConversionCommand command, EvidenceProgressListener progress,
            CancellationSignal cancellation) {
        Objects.requireNonNull(command, "command");
        EvidenceProgressListener listener =
                progress == null ? EvidenceProgressListener.NOOP : progress;
        CancellationSignal signal = cancellation == null ? CancellationSignal.NEVER : cancellation;
        RunResourceDomain resources = new RunResourceDomain();
        if (signal.isCancelled()) {
            resources.closeExactlyOnce(CloseReason.CANCELLED);
            return CompletableFuture.completedFuture(new DirectImageConversionOutcome.Cancelled());
        }
        GroundedRunControlPort.RunIdentity identity = identity(command);
        boolean runStarted = false;
        try {
            runs.start(identity);
            runStarted = true;
            return preparation.prepare(command.source(), resources, listener, signal)
                    .handle((outcome, failure) ->
                            finish(command, identity, resources, signal, outcome, failure));
        } catch (RuntimeException preparationFailure) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            // A failed start may refer to another active owner; cancel only a run we started.
            if (runStarted) cancel(identity);
            return CompletableFuture.completedFuture(
                    unavailable(DirectFailureKind.DEPENDENCY,
                            "DIRECT_PREPARATION_UNAVAILABLE"));
        }
    }

    private DirectImageConversionOutcome finish(DirectImageConversionCommand command,
                                                GroundedRunControlPort.RunIdentity identity,
                                                RunResourceDomain resources,
                                                CancellationSignal cancellation,
                                                DirectSourceOutcome outcome,
                                                Throwable failure) {
        if (failure != null || outcome == null) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            cancel(identity);
            return unavailable(DirectFailureKind.DEPENDENCY,
                    "DIRECT_PREPARATION_UNAVAILABLE");
        }
        if (outcome instanceof DirectSourceOutcome.Cancelled) {
            resources.closeExactlyOnce(CloseReason.CANCELLED);
            cancel(identity);
            return new DirectImageConversionOutcome.Cancelled();
        }
        if (outcome instanceof DirectSourceOutcome.Unavailable unavailable) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            cancel(identity);
            return unavailable(unavailable.failureKind(), unavailable.reason());
        }
        if (outcome instanceof DirectSourceOutcome.NeedsConfirmation confirmation) {
            resources.closeExactlyOnce(CloseReason.COMPLETED);
            cancel(identity);
            return new DirectImageConversionOutcome.NeedsConfirmation(
                    confirmation.reasons(), confirmation.observedValues());
        }
        if (outcome instanceof DirectSourceOutcome.Rejected rejected) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            cancel(identity);
            return new DirectImageConversionOutcome.Rejected(rejected.reasons());
        }
        if (cancellation.isCancelled()) {
            // Close the last race window between preparation completion and durable commit.
            resources.closeExactlyOnce(CloseReason.CANCELLED);
            cancel(identity);
            return new DirectImageConversionOutcome.Cancelled();
        }
        DirectSourceOutcome.Prepared prepared = (DirectSourceOutcome.Prepared) outcome;
        CanvasCommitResult committed;
        try {
            committed = commits.commit(commitCommand(command, prepared), resources);
        } catch (RuntimeException commitFailure) {
            // Commit policy and infrastructure exceptions share the same terminal cleanup owner.
            resources.closeExactlyOnce(CloseReason.FAILED);
            cancel(identity);
            return unavailable(DirectFailureKind.COMMIT, "CANVAS_COMMIT_FAILED");
        }
        if (committed.committed()) {
            return new DirectImageConversionOutcome.Committed(
                    committed.canvasXml(), committed.saveResult());
        }
        cancel(identity);
        if (committed.failureKind() == CanvasCommitResult.FailureKind.UNAVAILABLE) {
            return unavailable(DirectFailureKind.COMMIT,
                    committed.errors().isEmpty()
                            ? "CANVAS_COMMIT_FAILED" : committed.errors().get(0));
        }
        return new DirectImageConversionOutcome.Rejected(committed.errors());
    }

    private CanvasCommitCommand commitCommand(DirectImageConversionCommand command,
                                              DirectSourceOutcome.Prepared prepared) {
        CanvasMutationCommand mutation = new CanvasMutationCommand(
                CanvasMutationPurpose.DIRECT_IMAGE_CONVERSION,
                command.currentXml(), prepared.mxGraphModelXml(), command.diagramType(),
                CanvasMutationAuthorization.unrestricted(), command.userId(), command.diagramId(),
                command.expectedVersion(), command.expectedContentHash());
        return new CanvasCommitCommand(mutation, command.source().requestId(),
                command.source().runId(), prepared.evidenceAccess(),
                prepared.citationBindings(), true, true);
    }

    private GroundedRunControlPort.RunIdentity identity(DirectImageConversionCommand command) {
        return new GroundedRunControlPort.RunIdentity(command.userId(),
                command.source().requestId(), command.source().runId());
    }

    private DirectImageConversionOutcome.Unavailable unavailable(
            DirectFailureKind kind, String reason) {
        return new DirectImageConversionOutcome.Unavailable(kind, reason);
    }

    private void cancel(GroundedRunControlPort.RunIdentity identity) {
        try {
            runs.cancel(identity);
        } catch (RuntimeException ignored) {
            // The primary typed outcome remains stable even if cleanup loses a race.
        }
    }
}
