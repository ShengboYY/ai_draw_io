package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.citation.model.valobj.CitationBinding;
import org.zipp.ai.domain.citation.model.valobj.StatementKind;
import org.zipp.ai.domain.citation.model.valobj.SupportAtom;
import org.zipp.ai.domain.citation.model.valobj.SupportAtomRole;
import org.zipp.ai.domain.citation.model.valobj.SupportType;
import org.zipp.ai.domain.grounding.EvidenceAccessContext;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.material.port.MaterialPageAccessPort;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.EvidenceBundle;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.EvidenceOrigin;
import org.zipp.ai.domain.retrieval.EvidenceProgressListener;
import org.zipp.ai.domain.retrieval.EvidenceSupportRole;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionCommand;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionService;
import org.zipp.ai.domain.retrieval.ResolvedSource;
import org.zipp.ai.domain.retrieval.ResolvedSourceSet;
import org.zipp.ai.domain.retrieval.RunResourceDomain;
import org.zipp.ai.domain.retrieval.SourceMode;
import org.zipp.ai.domain.retrieval.port.AuthorizedSourceSet;
import org.zipp.ai.domain.retrieval.port.EvidenceReadLeaseCoordinator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Prepares a single image directly. It intentionally has no retrieval or index dependency.
 */
public final class DefaultDirectSourcePreparationModule implements DirectSourcePreparationModule {
    private static final int MAX_VISUAL_RETRIES = 1;

    private final VisualObservationModule observations;
    private final ImageToDiagramModule converter;
    private final RequestSourceResolutionService sourceResolution;
    private final EvidenceReadLeaseCoordinator leases;
    private final MaterialPageAccessPort pages;
    private final DirectDiagramProjectionVerifier projectionVerifier =
            new DirectDiagramProjectionVerifier();
    private final DirectDiagramGeometryRepairer geometryRepairer =
            new DirectDiagramGeometryRepairer();
    private final DefaultImageToDiagramModule projectionRules =
            new DefaultImageToDiagramModule();

    public DefaultDirectSourcePreparationModule(VisualObservationModule observations,
                                                ImageToDiagramModule converter,
                                                RequestSourceResolutionService sourceResolution,
                                                EvidenceReadLeaseCoordinator leases,
                                                MaterialPageAccessPort pages) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.sourceResolution = Objects.requireNonNull(sourceResolution, "sourceResolution");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    @Override
    public java.util.concurrent.CompletionStage<DirectSourceOutcome> prepare(
            DirectSourceCommand command, RunResourceDomain resources,
            EvidenceProgressListener progress, CancellationSignal cancellation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resources, "resources");
        EvidenceProgressListener listener =
                progress == null ? EvidenceProgressListener.NOOP : progress;
        CancellationSignal signal = cancellation == null ? CancellationSignal.NEVER : cancellation;
        if (stopped(resources, signal)) {
            return CompletableFuture.completedFuture(new DirectSourceOutcome.Cancelled());
        }
        SourceResolutionResult resolved = resolve(command, resources);
        if (resolved.outcome() != null) {
            return CompletableFuture.completedFuture(resolved.outcome());
        }
        if (!command.clarifications().isEmpty()
                && !resolved.target().versionId().equals(command.confirmationSourceVersionId())) {
            return CompletableFuture.completedFuture(
                    new DirectSourceOutcome.Rejected(List.of("DIRECT_CONFIRMATION_SOURCE_MISMATCH")));
        }
        VisualObservationCommand observationCommand = new VisualObservationCommand(
                command.owner(), command.requestId(), command.runId(),
                VisualObservationPurpose.DIAGRAM_RECONSTRUCTION, observationQuestion(command),
                List.of(resolved.target()), 32);
        return observeAndPrepare(command, resolved.target(), observationCommand, resources,
                listener, signal, MAX_VISUAL_RETRIES);
    }

    private java.util.concurrent.CompletionStage<DirectSourceOutcome> observeAndPrepare(
            DirectSourceCommand command,
            VisualObservationTarget target,
            VisualObservationCommand observationCommand,
            RunResourceDomain resources,
            EvidenceProgressListener progress,
            CancellationSignal signal,
            int remainingRetries) {
        java.util.concurrent.CompletionStage<VisualObservationOutcome> observationStage;
        try {
            observationStage = observations.observe(observationCommand, resources, signal);
        } catch (RuntimeException failure) {
            observationStage = CompletableFuture.failedFuture(failure);
        }
        return observationStage.handle((observation, failure) -> {
            if (failure != null) {
                return stopped(resources, signal)
                        ? new DirectSourceOutcome.Cancelled()
                        : unavailable(DirectFailureKind.VISUAL_PROVIDER,
                                "VISUAL_PROVIDER_UNAVAILABLE");
            }
            return prepareObserved(command, target, resources, progress, signal, observation);
        }).thenCompose(outcome -> {
            if (remainingRetries > 0 && retryable(outcome) && !stopped(resources, signal)) {
                // Retry the exact immutable image once; never broaden the source set.
                progress.onProgress("direct_visual_retry", 1, 1);
                return observeAndPrepare(command, target, observationCommand, resources,
                        progress, signal, remainingRetries - 1);
            }
            return CompletableFuture.completedFuture(outcome);
        });
    }

    private String observationQuestion(DirectSourceCommand command) {
        if (command.clarifications().isEmpty()) return command.question();
        // Values are domain enums and reason codes are restricted, so this remains bounded user data.
        String resolved = command.clarifications().stream()
                .map(value -> value.reasonCode() + "=" + value.resolution().name())
                .collect(java.util.stream.Collectors.joining(", "));
        return command.question()
                + "\n\nUser-confirmed image clarifications (constraints, not source facts): "
                + resolved;
    }

    private DirectSourceOutcome prepareObserved(DirectSourceCommand command,
                                                VisualObservationTarget target,
                                                RunResourceDomain resources,
                                                EvidenceProgressListener progress,
                                                CancellationSignal signal,
                                                VisualObservationOutcome observation) {
        try {
            if (stopped(resources, signal)) return new DirectSourceOutcome.Cancelled();
            progress.onProgress("direct_visual_observation", 1, 1);
            if (observation instanceof VisualObservationOutcome.Cancelled) {
                return new DirectSourceOutcome.Cancelled();
            }
            if (observation instanceof VisualObservationOutcome.Unavailable unavailable) {
                return unavailable(DirectFailureKind.VISUAL_PROVIDER, unavailable.reason());
            }
            if (observation instanceof VisualObservationOutcome.Rejected rejected) {
                if ("INVALID_DIAGRAM_EVIDENCE_ANCHOR".equals(rejected.reason())) {
                    return unavailable(DirectFailureKind.VISUAL_PROVIDER,
                            "VISUAL_PROVIDER_OUTPUT_INVALID");
                }
                return new DirectSourceOutcome.Rejected(List.of(rejected.reason()));
            }
            if (observation instanceof VisualObservationOutcome.Gap gap) {
                return gapConfirmation(gap.reasons());
            }
            if (!(observation instanceof VisualObservationOutcome.DiagramVerified verified)) {
                return unavailable(DirectFailureKind.VISUAL_PROVIDER,
                        "VISUAL_PROVIDER_OUTPUT_INVALID");
            }
            ObservedDiagramGraph graph = verified.graph();
            DirectDiagramGeometryRepairer.RepairResult repair = geometryRepairer.repair(graph);
            if (repair.unresolved()) {
                return unavailable(DirectFailureKind.PROJECTION,
                        "DIRECT_GEOMETRY_REPAIR_UNAVAILABLE");
            }
            graph = repair.graph();
            if (repair.changed()) progress.onProgress("direct_geometry_repair", 1, 1);
            ImageToDiagramCommand projectionCommand =
                    new ImageToDiagramCommand(graph, command.clarifications());
            // The trusted canonical decision prevents an injected projector from bypassing
            // topology validation or a required user-confirmation boundary.
            ImageToDiagramOutcome canonical = projectionRules.convert(projectionCommand);
            if (canonical instanceof ImageToDiagramOutcome.NeedsConfirmation needs) {
                return new DirectSourceOutcome.NeedsConfirmation(
                        needs.reasons(), needs.observedValues());
            }
            if (canonical instanceof ImageToDiagramOutcome.Rejected) {
                return unavailable(DirectFailureKind.PROJECTION, "DIRECT_PROJECTION_INVALID");
            }
            ImageToDiagramOutcome conversion = converter.convert(projectionCommand);
            progress.onProgress("direct_diagram_projection", 1, 1);
            if (!(conversion instanceof ImageToDiagramOutcome.Converted converted)) {
                // Canonical validation succeeded, so a different projector outcome is a system fault.
                return unavailable(DirectFailureKind.PROJECTION, "DIRECT_PROJECTION_INVALID");
            }
            ObservedDiagramGraph expectedGraph =
                    ((ImageToDiagramOutcome.Converted) canonical).graph();
            DirectDiagramProjectionVerification verification = projectionVerifier.verify(
                    expectedGraph, converted.mxGraphModelXml(), converted.cellIds());
            progress.onProgress("direct_visual_verification", 1, 1);
            if (!verification.verified()) {
                return unavailable(DirectFailureKind.PROJECTION,
                        "DIRECT_PROJECTION_VERIFICATION_FAILED");
            }
            CitationProjection citations = citations(command, target, expectedGraph);
            resources.markPrepared();
            return new DirectSourceOutcome.Prepared(
                    expectedGraph, converted.mxGraphModelXml(), converted.cellIds(),
                    citations.access(), citations.bindings());
        } catch (IllegalArgumentException exception) {
            return unavailable(DirectFailureKind.PROJECTION, "DIRECT_PROJECTION_INVALID");
        } catch (RuntimeException exception) {
            if (stopped(resources, signal)) return new DirectSourceOutcome.Cancelled();
            return unavailable(DirectFailureKind.PROJECTION, "DIRECT_PROJECTION_UNAVAILABLE");
        }
    }

    private boolean retryable(DirectSourceOutcome outcome) {
        return outcome instanceof DirectSourceOutcome.Unavailable unavailable
                && unavailable.failureKind().retryable();
    }

    private DirectSourceOutcome.Unavailable unavailable(DirectFailureKind kind, String reason) {
        return new DirectSourceOutcome.Unavailable(kind, reason);
    }

    private DirectSourceOutcome.NeedsConfirmation gapConfirmation(List<String> reasons) {
        Map<String, String> observedValues = new LinkedHashMap<>();
        reasons.stream()
                .map(reason -> reason == null ? "" : reason.trim())
                .filter(reason -> !reason.isBlank())
                .limit(5)
                .forEach(reason -> {
                    // Model prose stays display-only; the protocol receives a bounded stable code.
                    String reasonCode = "OBSERVATION_GAP:"
                            + DirectObservationFingerprint.of(reason).substring(0, 24);
                    observedValues.putIfAbsent(reasonCode, reason);
                });
        return new DirectSourceOutcome.NeedsConfirmation(
                List.copyOf(observedValues.keySet()), observedValues);
    }

    private SourceResolutionResult resolve(DirectSourceCommand command,
                                           RunResourceDomain resources) {
        ResolvedSourceSet resolved;
        if (command.resolvedSources() != null) {
            // One frozen authorization snapshot must be shared by routing, Direct, and Retrieval.
            resolved = command.resolvedSources();
        } else {
            if (command.attachmentUploadId().isEmpty()) {
                return unauthorizedOrNotReady();
            }
            try {
                resolved = sourceResolution.resolve(new RequestSourceResolutionCommand(
                        command.owner(), command.diagramId(), command.conversationId(), command.runId(),
                        command.sourceMode(), List.of(command.attachmentUploadId()),
                        command.selectedVersionIds()));
            } catch (RuntimeException failure) {
                return SourceResolutionResult.failed(
                        unavailable(DirectFailureKind.DEPENDENCY,
                                "DIRECT_SOURCE_RESOLUTION_UNAVAILABLE"));
            }
        }
        if (resolved.resolutionFailed()) {
            return SourceResolutionResult.failed(
                    unavailable(DirectFailureKind.DEPENDENCY,
                            "DIRECT_SOURCE_RESOLUTION_UNAVAILABLE"));
        }
        String primaryVersionId = command.primaryDirectVersionId();
        if (primaryVersionId.isEmpty()) {
            List<String> compatibleCandidates = resolved.sources().stream()
                    .filter(ResolvedSource::directReadable)
                    .map(ResolvedSource::versionId).distinct().toList();
            primaryVersionId = compatibleCandidates.size() == 1 ? compatibleCandidates.get(0) : "";
        }
        String selectedVersionId = primaryVersionId;
        List<ResolvedSource> directSources = resolved.sources().stream()
                // The primary version was selected from this exact authorization snapshot by the Planner.
                .filter(source -> source.versionId().equals(selectedVersionId))
                .toList();
        if (directSources.size() != 1) {
            return unauthorizedOrNotReady();
        }
        ResolvedSource source = directSources.get(0);
        // Direct reconstruction is intentionally single-image only; PDF pages use the RAG path.
        if (!source.directReadable()) {
            return unauthorizedOrNotReady();
        }
        try {
            resources.attach(leases.acquire(command.owner(), command.runId(),
                    new AuthorizedSourceSet(command.owner(), SourceMode.EXPLICIT_ONLY,
                            List.of(source.authorizedSource()))));
        } catch (RuntimeException failure) {
            return SourceResolutionResult.failed(
                    unavailable(DirectFailureKind.DEPENDENCY,
                            "DIRECT_ATTACHMENT_LEASE_UNAVAILABLE"));
        }
        StoredArtifact artifact;
        try {
            artifact = pages.findPreviewArtifact(command.owner(), source.materialId(),
                    source.versionId(), source.revisionId(), 1).orElse(null);
            if (artifact == null) {
                return SourceResolutionResult.failed(unavailable(DirectFailureKind.DEPENDENCY,
                        "DIRECT_ATTACHMENT_ARTIFACT_UNAVAILABLE"));
            }
        } catch (RuntimeException failure) {
            return SourceResolutionResult.failed(unavailable(DirectFailureKind.DEPENDENCY,
                    "DIRECT_ATTACHMENT_ARTIFACT_UNAVAILABLE"));
        }
        try {
            VisualObservationTarget target = new VisualObservationTarget(
                    "direct-image-" + source.revisionId() + "-p1", source.materialId(),
                    source.versionId(), source.revisionId(), 1, source.kind(), artifact);
            return new SourceResolutionResult(target, null);
        } catch (IllegalArgumentException invalid) {
            return SourceResolutionResult.failed(new DirectSourceOutcome.Rejected(
                    List.of("DIRECT_ATTACHMENT_INVALID")));
        }
    }

    private SourceResolutionResult unauthorizedOrNotReady() {
        return SourceResolutionResult.failed(new DirectSourceOutcome.Rejected(
                List.of("DIRECT_ATTACHMENT_NOT_AUTHORIZED_OR_READY")));
    }

    private CitationProjection citations(DirectSourceCommand command,
                                         VisualObservationTarget target,
                                         ObservedDiagramGraph graph) {
        List<EvidenceBundleItem> items = new ArrayList<>();
        List<CitationBinding> bindings = new ArrayList<>();
        for (ObservedDiagramGraph.Group group : graph.groups()) {
            addNodeCitation(target, DirectDiagramCellIds.group(group.id()), "group-" + group.id(),
                    group.label(), group.evidenceId(), items, bindings);
        }
        for (ObservedDiagramGraph.Node node : graph.nodes()) {
            addNodeCitation(target, DirectDiagramCellIds.node(node.id()), "node-" + node.id(),
                    node.label(), node.evidenceId(), items, bindings);
        }
        java.util.Map<String, ObservedDiagramGraph.Node> nodes = new java.util.LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        for (ObservedDiagramGraph.Edge edge : graph.edges()) {
            ObservedDiagramGraph.Node source = nodes.get(edge.resolvedSourceId());
            ObservedDiagramGraph.Node targetNode = nodes.get(edge.resolvedTargetId());
            String statement = String.join(" ", List.of(source.label(), edge.label(), targetNode.label()))
                    .replaceAll("\\s+", " ").trim();
            String citationKey = "direct-edge-" + edge.id();
            items.add(item(target, citationKey, edge.evidenceId(), statement));
            bindings.add(new CitationBinding(DirectDiagramCellIds.edge(edge.id()),
                    "direct-edge-statement-" + edge.id(), StatementKind.EDGE_RELATION,
                    statement, DirectDiagramCellIds.node(source.id()),
                    DirectDiagramCellIds.node(targetNode.id()), List.of(citationKey),
                    List.of(new SupportAtom("direct-edge-atom-" + edge.id(), citationKey,
                            statement, SupportAtomRole.RELATION)), SupportType.EVIDENCE));
        }
        EvidenceBundle bundle = new EvidenceBundle("direct-bundle-" + command.runId(),
                command.requestId(), command.runId(), SourceMode.EXPLICIT_ONLY, items);
        return new CitationProjection(EvidenceAccessContext.from(bundle, false), bindings);
    }

    private void addNodeCitation(VisualObservationTarget target, String cellId, String localKey,
                                 String statement, String evidenceId,
                                 List<EvidenceBundleItem> items,
                                 List<CitationBinding> bindings) {
        String citationKey = "direct-" + localKey;
        items.add(item(target, citationKey, evidenceId, statement));
        bindings.add(new CitationBinding(cellId, "direct-statement-" + localKey,
                StatementKind.NODE_TEXT, statement, null, null, List.of(citationKey),
                List.of(new SupportAtom("direct-atom-" + localKey, citationKey,
                        statement, SupportAtomRole.DIRECT_QUOTE)), SupportType.EVIDENCE));
    }

    private EvidenceBundleItem item(VisualObservationTarget target, String citationKey,
                                    String evidenceId, String statement) {
        return new EvidenceBundleItem(citationKey, evidenceId, target.materialId(),
                target.versionId(), target.revisionId(), target.sourceLabel(), target.pageNumber(),
                "VISUAL", statement, EvidenceSupportRole.SUPPORT,
                EvidenceOrigin.DIRECT_ATTACHMENT);
    }

    private boolean stopped(RunResourceDomain resources, CancellationSignal signal) {
        return resources.isClosed() || signal.isCancelled() || Thread.currentThread().isInterrupted();
    }

    private record CitationProjection(EvidenceAccessContext access,
                                      List<CitationBinding> bindings) {}

    private record SourceResolutionResult(VisualObservationTarget target,
                                          DirectSourceOutcome outcome) {
        private static SourceResolutionResult failed(DirectSourceOutcome outcome) {
            return new SourceResolutionResult(null, outcome);
        }
    }
}
