package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Repository;
import org.zipp.ai.application.turn.DirectVisionPort;
import org.zipp.ai.application.turn.planning.BoundSourcePlan;
import org.zipp.ai.application.turn.planning.SourceAwareDrawPlan;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.multimodal.DirectSourceCommand;
import org.zipp.ai.domain.multimodal.DirectSourceOutcome;
import org.zipp.ai.domain.multimodal.DirectSourcePreparationModule;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.CloseReason;
import org.zipp.ai.domain.retrieval.EvidenceProgressListener;
import org.zipp.ai.domain.retrieval.RunResourceDomain;
import org.zipp.ai.domain.retrieval.SourceMode;
import org.zipp.ai.domain.retrieval.port.RequestSourceSnapshotStore;

import java.util.List;
import java.util.Objects;

/** Executes the exact-artifact visual module and durably hands its projection to Direct generation. */
@Repository
@ConditionalOnBean(DirectSourcePreparationModule.class)
public class MySqlDirectVisionAdapter implements DirectVisionPort {

    private final DirectSourcePreparationModule preparation;
    private final RequestSourceSnapshotStore snapshots;
    private final MySqlDirectPreparationStore store;

    public MySqlDirectVisionAdapter(DirectSourcePreparationModule preparation,
                                    RequestSourceSnapshotStore snapshots,
                                    MySqlDirectPreparationStore store) {
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Observation observe(Request request) {
        if (request.context() == null) {
            throw new IllegalArgumentException("DIRECT_CONTEXT_REQUIRED");
        }
        BoundSourcePlan plan = request.plan();
        String versionId = directVersion(plan);
        CatalogOwner owner = new CatalogOwner(OwnerType.USER, request.attempt().key().ownerKey());
        String runId = request.attempt().key().turnId();
        RequestSourceSnapshotStore.StoredSnapshot snapshot = snapshots.find(owner, runId)
                .orElseThrow(() -> new IllegalStateException("SOURCE_SNAPSHOT_NOT_FOUND"));
        DirectSourceCommand command = new DirectSourceCommand(
                owner, runId, runId, request.context().request().diagramId(),
                request.attempt().key().canonicalConversationId(), "", List.of(versionId),
                SourceMode.EXPLICIT_ONLY, request.context().request().instruction().value(),
                "", List.of(), snapshot.sources(), versionId);
        RunResourceDomain resources = new RunResourceDomain();
        CloseReason closeReason = CloseReason.FAILED;
        try {
            DirectSourceOutcome outcome = preparation.prepare(
                    command, resources, EvidenceProgressListener.NOOP, CancellationSignal.NEVER)
                    .toCompletableFuture().join();
            if (!(outcome instanceof DirectSourceOutcome.Prepared prepared)) {
                throw new IllegalStateException("DIRECT_VISION_PREPARATION_" + outcomeCode(outcome));
            }
            String fingerprint = directFingerprint(plan);
            store.save(request.attempt(), request.artifactLeaseRef(), plan.identity(), runId,
                    fingerprint, prepared.mxGraphModelXml());
            closeReason = CloseReason.COMPLETED;
            return new Observation(request.artifactLeaseRef(), fingerprint);
        } finally {
            resources.closeExactlyOnce(closeReason);
        }
    }

    private String directVersion(BoundSourcePlan plan) {
        if (plan.plan() instanceof SourceAwareDrawPlan.Direct direct) {
            return direct.direct().candidate().candidateRef();
        }
        if (plan.plan() instanceof SourceAwareDrawPlan.OptionalComposite composite) {
            return composite.direct().candidate().candidateRef();
        }
        if (plan.plan() instanceof SourceAwareDrawPlan.RequiredComposite composite) {
            return composite.direct().candidate().candidateRef();
        }
        throw new IllegalArgumentException("DIRECT_PLAN_REQUIRED");
    }

    private String directFingerprint(BoundSourcePlan plan) {
        if (plan.plan() instanceof SourceAwareDrawPlan.Direct direct) {
            return direct.direct().candidate().observationFingerprint();
        }
        if (plan.plan() instanceof SourceAwareDrawPlan.OptionalComposite composite) {
            return composite.direct().candidate().observationFingerprint();
        }
        if (plan.plan() instanceof SourceAwareDrawPlan.RequiredComposite composite) {
            return composite.direct().candidate().observationFingerprint();
        }
        throw new IllegalArgumentException("DIRECT_PLAN_REQUIRED");
    }

    private String outcomeCode(DirectSourceOutcome outcome) {
        if (outcome instanceof DirectSourceOutcome.Unavailable unavailable) return unavailable.reason();
        if (outcome instanceof DirectSourceOutcome.Rejected rejected) {
            return rejected.reasons().stream().findFirst().orElse("REJECTED");
        }
        if (outcome instanceof DirectSourceOutcome.NeedsConfirmation) return "CONFIRMATION_REQUIRED";
        if (outcome instanceof DirectSourceOutcome.Cancelled) return "CANCELLED";
        return "INVALID";
    }
}
