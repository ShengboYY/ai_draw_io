package org.zipp.ai.infrastructure.adapter.repository;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Repository;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.SourceAwarePreparationPort;
import org.zipp.ai.application.turn.SourceAwarePreparedExecution;
import org.zipp.ai.application.turn.SourceCommitBinding;
import org.zipp.ai.application.turn.ValidatedCitationManifest;
import org.zipp.ai.application.turn.classification.OutputIntent;
import org.zipp.ai.application.turn.planning.BoundSourcePlan;
import org.zipp.ai.application.turn.planning.DirectCandidateOrigin;
import org.zipp.ai.application.turn.planning.SourceAwareDrawPlan;
import org.zipp.ai.application.turn.planning.SourcePlanDecision;
import org.zipp.ai.domain.account.model.valobj.OwnerType;
import org.zipp.ai.domain.material.model.valobj.CatalogOwner;
import org.zipp.ai.domain.retrieval.CanvasProbe;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.CloseReason;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.EvidencePreparationCommand;
import org.zipp.ai.domain.retrieval.EvidencePreparationModule;
import org.zipp.ai.domain.retrieval.EvidenceProgressListener;
import org.zipp.ai.domain.retrieval.PreparedEvidence;
import org.zipp.ai.domain.retrieval.PreparationOutcome;
import org.zipp.ai.domain.retrieval.ResolvedSource;
import org.zipp.ai.domain.retrieval.ResolvedSourceSet;
import org.zipp.ai.domain.retrieval.RunResourceDomain;
import org.zipp.ai.domain.retrieval.SourceMode;
import org.zipp.ai.domain.retrieval.ValidatedSelection;
import org.zipp.ai.domain.retrieval.port.RequestSourceSnapshotStore;

import java.util.List;
import java.util.Objects;

/**
 * Turns a planner-bound source identity into an owner-fenced Direct or evidence capability. The
 * source snapshot is the only authorization input; prepared display evidence is persisted before
 * a handler is allowed to call a model.
 */
@Repository
public final class MySqlSourceAwarePreparationAdapter implements SourceAwarePreparationPort {

    private final RequestSourceSnapshotStore snapshots;
    private final ObjectProvider<EvidencePreparationModule> evidencePreparations;
    private final MySqlEvidencePreparationStore evidenceStore;

    public MySqlSourceAwarePreparationAdapter(
            RequestSourceSnapshotStore snapshots,
            ObjectProvider<EvidencePreparationModule> evidencePreparations,
            MySqlEvidencePreparationStore evidenceStore
    ) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.evidencePreparations = Objects.requireNonNull(evidencePreparations, "evidencePreparations");
        this.evidenceStore = Objects.requireNonNull(evidenceStore, "evidenceStore");
    }

    @Override
    public Outcome prepare(Request request) {
        Objects.requireNonNull(request, "request");
        if (request.plan() instanceof SourcePlanDecision.RequiredSourceReady required
                && required.bound() == null) {
            return new Outcome.Rejected("SOURCE_PLAN_NOT_FROZEN");
        }
        BoundSourcePlan bound = bound(request.plan());
        if (bound == null) return new Outcome.Rejected("SOURCE_AWARE_PLAN_NOT_PREPARED");
        String runId = request.probeCommand().binding().turn().turnId();
        CatalogOwner owner = new CatalogOwner(OwnerType.USER, request.attempt().key().ownerKey());
        RequestSourceSnapshotStore.StoredSnapshot snapshot = snapshots.find(owner, runId).orElse(null);
        if (snapshot == null) return new Outcome.Rejected("SOURCE_SNAPSHOT_NOT_FOUND");
        try {
            if (directOnly(bound)) return prepareDirect(request, bound, snapshot);
            if (evidencePlan(bound)) return prepareEvidence(request, bound, snapshot);
            return new Outcome.Rejected("SOURCE_AWARE_PLAN_NOT_PREPARED");
        } catch (RuntimeException failure) {
            return new Outcome.Rejected("SOURCE_AWARE_PREPARATION_FAILED");
        }
    }

    private BoundSourcePlan bound(SourcePlanDecision decision) {
        if (decision instanceof SourcePlanDecision.SourceReady ready) return ready.bound();
        if (decision instanceof SourcePlanDecision.DirectOnlyReady ready) return ready.bound();
        if (decision instanceof SourcePlanDecision.RequiredSourceReady ready) return ready.bound();
        return null;
    }

    private String directVersion(BoundSourcePlan bound) {
        if (bound.plan() instanceof SourceAwareDrawPlan.Direct direct) {
            return direct.direct().candidate().candidateRef();
        }
        if (bound.plan() instanceof SourceAwareDrawPlan.OptionalComposite composite) {
            return composite.direct().candidate().candidateRef();
        }
        if (bound.plan() instanceof SourceAwareDrawPlan.RequiredComposite composite) {
            return composite.direct().candidate().candidateRef();
        }
        throw new IllegalArgumentException("DIRECT_PLAN_REQUIRED");
    }

    private DirectCandidateOrigin directOrigin(BoundSourcePlan bound) {
        if (bound.plan() instanceof SourceAwareDrawPlan.Direct direct) {
            return direct.direct().candidate().origin();
        }
        if (bound.plan() instanceof SourceAwareDrawPlan.OptionalComposite composite) {
            return composite.direct().candidate().origin();
        }
        if (bound.plan() instanceof SourceAwareDrawPlan.RequiredComposite composite) {
            return composite.direct().candidate().origin();
        }
        throw new IllegalArgumentException("DIRECT_PLAN_REQUIRED");
    }

    private Outcome prepareDirect(Request request, BoundSourcePlan bound,
                                  RequestSourceSnapshotStore.StoredSnapshot snapshot) {
        String sourceIdentity = directVersion(bound);
        ResolvedSource source = findSource(snapshot.sources(), sourceIdentity);
        if (source == null || !source.directReadable()) {
            return new Outcome.Rejected("DIRECT_SOURCE_NOT_IN_SNAPSHOT");
        }
        String runId = request.probeCommand().binding().turn().turnId();
        SourceCommitBinding binding = binding(bound, snapshot, runId, source.versionId());
        String preparedRef = "direct-prepared-" + ModelInputBinding.digestOf(
                request.attempt().key().ownerKey(), request.attempt().key().canonicalConversationId(),
                request.attempt().key().turnId(), bound.identity().planFingerprint());
        return new Outcome.Ready(new SourceAwarePreparedExecution.Direct(
                bound, binding, preparedRef, source.versionId(), directOrigin(bound)));
    }

    private Outcome prepareEvidence(Request request, BoundSourcePlan bound,
                                    RequestSourceSnapshotStore.StoredSnapshot snapshot) {
        EvidencePreparationModule module = evidencePreparations.getIfAvailable();
        if (module == null) return new Outcome.Rejected("EVIDENCE_PREPARATION_NOT_AVAILABLE");
        List<String> selectedVersions = retrievalVersions(bound);
        if (selectedVersions.isEmpty()) return new Outcome.Rejected("RETRIEVAL_SOURCE_NOT_BOUND");
        CatalogOwner owner = new CatalogOwner(OwnerType.USER, request.attempt().key().ownerKey());
        String runId = request.probeCommand().binding().turn().turnId();
        EvidencePreparationCommand command = new EvidencePreparationCommand(
                owner,
                request.context().request().diagramId(),
                request.attempt().key().canonicalConversationId(),
                runId,
                runId,
                request.context().request().instruction().value(),
                CanvasProbe.unavailableProbe(),
                ValidatedSelection.empty(),
                snapshot.sources().mode() == SourceMode.NONE
                        ? SourceMode.EXPLICIT : snapshot.sources().mode(),
                snapshot.sources(),
                selectedVersions,
                "REQUIRED",
                "NONE",
                "NONE",
                bound.plan() instanceof SourceAwareDrawPlan.RequiredComposite
                        || bound.plan() instanceof SourceAwareDrawPlan.OptionalComposite);
        RunResourceDomain resources = new RunResourceDomain();
        PreparationOutcome outcome = module.prepare(
                        command, resources, EvidenceProgressListener.NOOP, CancellationSignal.NEVER)
                .toCompletableFuture().join();
        if (!(outcome instanceof PreparationOutcome.Ready ready)) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            return new Outcome.Rejected("EVIDENCE_PREPARATION_" + outcomeCode(outcome));
        }
        PreparedEvidence evidence = ready.preparedEvidence();
        try {
            List<EvidenceBundleItem> items = evidence.bundle().items();
            ValidatedCitationManifest citations = citations(bound, runId, items);
            SourceCommitBinding binding = binding(bound, snapshot, runId, String.join(",", selectedVersions));
            String preparedRef = "evidence-prepared-" + ModelInputBinding.digestOf(
                    request.attempt().key().ownerKey(), request.attempt().key().canonicalConversationId(),
                    request.attempt().key().turnId(), bound.identity().planFingerprint());
            evidenceStore.save(request.attempt(), preparedRef, bound.identity(), runId,
                    citations.manifestDigest(), items);
            if (request.outputIntent() == OutputIntent.TEXT
                    || request.outputIntent() == OutputIntent.REVIEW) {
                return new Outcome.Ready(new SourceAwarePreparedExecution.EvidenceAnswer(
                        binding, preparedRef, citations));
            }
            return new Outcome.Ready(new SourceAwarePreparedExecution.Grounded(
                    bound, binding, preparedRef, citations, java.util.Optional.empty()));
        } finally {
            evidence.close();
        }
    }

    private SourceCommitBinding binding(BoundSourcePlan bound,
                                        RequestSourceSnapshotStore.StoredSnapshot snapshot,
                                        String runId,
                                        String sourceIdentity) {
        String snapshotDigest = ModelInputBinding.digestOf(
                snapshot.declarationFingerprint(), snapshot.sources().mode().name(), sourceIdentity);
        String entryDigest = ModelInputBinding.digestOf(
                bound.identity().planFingerprint(), bound.entry().getClass().getName());
        return new SourceCommitBinding(bound.identity(), runId, snapshotDigest, entryDigest);
    }

    private ValidatedCitationManifest citations(BoundSourcePlan bound, String runId,
                                                List<EvidenceBundleItem> items) {
        List<ValidatedCitationManifest.CandidateCitation> candidates = items.stream()
                .filter(item -> item.supportRole() == org.zipp.ai.domain.retrieval.EvidenceSupportRole.SUPPORT)
                .map(item -> new ValidatedCitationManifest.CandidateCitation(
                        item.citationKey(), item.citationKey(), ModelInputBinding.digestOf(item.text()),
                        true, List.of(new ValidatedCitationManifest.EvidenceLink(
                                item.citationKey(), item.evidenceId(), item.materialId(), item.versionId(),
                                item.revisionId(), item.origin().name()))))
                .toList();
        String digest = ModelInputBinding.digestOf(
                bound.identity().planFingerprint(), runId,
                items.stream().map(EvidenceBundleItem::evidenceId).reduce("", (left, right) -> left + "\u001f" + right));
        ValidatedCitationManifest.ValidationOutcome outcome = ValidatedCitationManifest.validate(
                digest, items.stream().map(EvidenceBundleItem::evidenceId).collect(java.util.stream.Collectors.toSet()),
                candidates);
        if (outcome instanceof ValidatedCitationManifest.ValidationOutcome.Rejected rejected) {
            throw new IllegalStateException(rejected.code());
        }
        return ((ValidatedCitationManifest.ValidationOutcome.Ready) outcome).manifest();
    }

    private List<String> retrievalVersions(BoundSourcePlan bound) {
        if (bound.plan() instanceof SourceAwareDrawPlan.Retrieval retrieval) {
            return retrieval.retrieval().stream()
                    .map(candidate -> candidate.candidateRef()).toList();
        }
        if (bound.plan() instanceof SourceAwareDrawPlan.OptionalComposite composite) {
            return compositeVersions(composite.direct().candidate().candidateRef(), composite.retrieval()
                    .stream().map(candidate -> candidate.candidateRef()).toList());
        }
        if (bound.plan() instanceof SourceAwareDrawPlan.RequiredComposite composite) {
            return compositeVersions(composite.direct().candidate().candidateRef(), composite.retrieval()
                    .stream().map(candidate -> candidate.candidateRef()).toList());
        }
        return List.of();
    }

    private List<String> compositeVersions(String directVersion, List<String> retrievalVersions) {
        // Composite reconstruction must retain the exact Direct source; otherwise a required
        // attachment silently disappears when the grounded branch prepares Retrieval evidence.
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(directVersion),
                        retrievalVersions.stream())
                .distinct()
                .toList();
    }

    private ResolvedSource findSource(ResolvedSourceSet snapshot, String versionId) {
        return snapshot.sources().stream().filter(source -> source.versionId().equals(versionId))
                .findFirst().orElse(null);
    }

    private boolean directOnly(BoundSourcePlan bound) {
        return bound.plan() instanceof SourceAwareDrawPlan.Direct
                || bound.plan() instanceof SourceAwareDrawPlan.OptionalComposite optional
                && optional.retrieval().isEmpty();
    }

    private boolean evidencePlan(BoundSourcePlan bound) {
        return bound.plan() instanceof SourceAwareDrawPlan.Retrieval
                || bound.plan() instanceof SourceAwareDrawPlan.OptionalComposite optional
                && !optional.retrieval().isEmpty()
                || bound.plan() instanceof SourceAwareDrawPlan.RequiredComposite;
    }

    private String outcomeCode(PreparationOutcome outcome) {
        if (outcome instanceof PreparationOutcome.InsufficientEvidence value) {
            return value.gaps().stream().findFirst().orElse("INSUFFICIENT");
        }
        if (outcome instanceof PreparationOutcome.DegradedDependency value) {
            return value.gaps().stream().findFirst().orElse("DEPENDENCY");
        }
        if (outcome instanceof PreparationOutcome.Waiting) return "WAITING";
        if (outcome instanceof PreparationOutcome.MaterialNotReady) return "MATERIAL_NOT_READY";
        if (outcome instanceof PreparationOutcome.Cancelled) return "CANCELLED";
        if (outcome instanceof PreparationOutcome.ClarificationNeeded value) return value.reason();
        return "FAILED";
    }
}
