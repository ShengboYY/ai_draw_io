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
import org.zipp.ai.domain.retrieval.RequestSourceOrigin;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionCommand;
import org.zipp.ai.domain.retrieval.RequestSourceResolutionService;
import org.zipp.ai.domain.retrieval.ResolvedSource;
import org.zipp.ai.domain.retrieval.ResolvedSourceSet;
import org.zipp.ai.domain.retrieval.RunResourceDomain;
import org.zipp.ai.domain.retrieval.SourceMode;
import org.zipp.ai.domain.retrieval.port.AuthorizedSourceSet;
import org.zipp.ai.domain.retrieval.port.EvidenceReadLeaseCoordinator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Prepares a single image directly. It intentionally has no retrieval or index dependency.
 */
public final class DefaultDirectSourcePreparationModule implements DirectSourcePreparationModule {
    private final VisualObservationModule observations;
    private final ImageToDiagramModule converter;
    private final RequestSourceResolutionService sourceResolution;
    private final EvidenceReadLeaseCoordinator leases;
    private final MaterialPageAccessPort pages;

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
        VisualObservationCommand observationCommand = new VisualObservationCommand(
                command.owner(), command.requestId(), command.runId(),
                VisualObservationPurpose.DIAGRAM_RECONSTRUCTION, command.question(),
                List.of(resolved.target()), 32);
        return observations.observe(observationCommand, resources, signal)
                .thenApply(outcome -> prepareObserved(command, resolved.target(), resources,
                        listener, signal, outcome));
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
                return new DirectSourceOutcome.Unavailable(unavailable.reason());
            }
            if (observation instanceof VisualObservationOutcome.Rejected rejected) {
                return new DirectSourceOutcome.Rejected(List.of(rejected.reason()));
            }
            if (observation instanceof VisualObservationOutcome.Gap gap) {
                return new DirectSourceOutcome.NeedsConfirmation(gap.reasons());
            }
            if (!(observation instanceof VisualObservationOutcome.DiagramVerified verified)) {
                return new DirectSourceOutcome.Rejected(List.of("NO_DIAGRAM_GRAPH"));
            }
            ObservedDiagramGraph graph = verified.graph();
            ImageToDiagramOutcome conversion =
                    converter.convert(new ImageToDiagramCommand(graph));
            progress.onProgress("direct_diagram_projection", 1, 1);
            if (conversion instanceof ImageToDiagramOutcome.NeedsConfirmation needs) {
                return new DirectSourceOutcome.NeedsConfirmation(needs.reasons());
            }
            if (conversion instanceof ImageToDiagramOutcome.Rejected rejected) {
                return new DirectSourceOutcome.Rejected(rejected.reasons());
            }
            ImageToDiagramOutcome.Converted converted =
                    (ImageToDiagramOutcome.Converted) conversion;
            CitationProjection citations = citations(command, target, graph);
            resources.markPrepared();
            return new DirectSourceOutcome.Prepared(
                    graph, converted.mxGraphModelXml(), converted.cellIds(),
                    citations.access(), citations.bindings());
        } catch (IllegalArgumentException exception) {
            return new DirectSourceOutcome.Rejected(List.of("INVALID_DIRECT_VISUAL_INPUT"));
        } catch (RuntimeException exception) {
            if (stopped(resources, signal)) return new DirectSourceOutcome.Cancelled();
            return new DirectSourceOutcome.Unavailable("DIRECT_VISUAL_PROVIDER_UNAVAILABLE");
        }
    }

    private SourceResolutionResult resolve(DirectSourceCommand command,
                                           RunResourceDomain resources) {
        ResolvedSourceSet resolved;
        try {
            resolved = sourceResolution.resolve(new RequestSourceResolutionCommand(
                    command.owner(), command.diagramId(), command.conversationId(), command.runId(),
                    command.sourceMode(), List.of(command.attachmentUploadId()),
                    command.selectedVersionIds()));
        } catch (RuntimeException failure) {
            return SourceResolutionResult.failed(
                    new DirectSourceOutcome.Unavailable("DIRECT_SOURCE_RESOLUTION_UNAVAILABLE"));
        }
        if (resolved.resolutionFailed()) {
            return SourceResolutionResult.failed(
                    new DirectSourceOutcome.Unavailable("DIRECT_SOURCE_RESOLUTION_UNAVAILABLE"));
        }
        if (resolved.processingSourceCount() > 0) {
            return SourceResolutionResult.failed(
                    new DirectSourceOutcome.Unavailable("DIRECT_ATTACHMENT_PROCESSING"));
        }
        List<ResolvedSource> attachments = resolved.sources().stream()
                .filter(source -> source.origin() == RequestSourceOrigin.ATTACHMENT)
                .toList();
        if (resolved.unavailableSourceCount() > 0 || attachments.size() != 1) {
            return unauthorizedOrNotReady();
        }
        ResolvedSource source = attachments.get(0);
        // Direct reconstruction is intentionally single-image only; PDF pages use the RAG path.
        if (!"READY".equals(source.state()) || !"IMAGE".equals(source.kind())
                || !source.hasVisual()) {
            return unauthorizedOrNotReady();
        }
        try {
            resources.attach(leases.acquire(command.owner(), command.runId(),
                    new AuthorizedSourceSet(command.owner(), SourceMode.EXPLICIT_ONLY,
                            List.of(source.authorizedSource()))));
        } catch (RuntimeException failure) {
            return SourceResolutionResult.failed(
                    new DirectSourceOutcome.Unavailable("DIRECT_ATTACHMENT_LEASE_UNAVAILABLE"));
        }
        StoredArtifact artifact;
        try {
            artifact = pages.findPreviewArtifact(command.owner(), source.materialId(),
                    source.versionId(), source.revisionId(), 1).orElse(null);
            if (artifact == null) {
                return SourceResolutionResult.failed(new DirectSourceOutcome.Unavailable(
                        "DIRECT_ATTACHMENT_ARTIFACT_UNAVAILABLE"));
            }
        } catch (RuntimeException failure) {
            return SourceResolutionResult.failed(new DirectSourceOutcome.Unavailable(
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
