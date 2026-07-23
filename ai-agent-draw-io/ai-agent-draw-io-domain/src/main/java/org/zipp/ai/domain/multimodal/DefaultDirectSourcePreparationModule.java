package org.zipp.ai.domain.multimodal;

import org.zipp.ai.domain.citation.model.valobj.CitationBinding;
import org.zipp.ai.domain.citation.model.valobj.StatementKind;
import org.zipp.ai.domain.citation.model.valobj.SupportAtom;
import org.zipp.ai.domain.citation.model.valobj.SupportAtomRole;
import org.zipp.ai.domain.citation.model.valobj.SupportType;
import org.zipp.ai.domain.grounding.EvidenceAccessContext;
import org.zipp.ai.domain.retrieval.CancellationSignal;
import org.zipp.ai.domain.retrieval.EvidenceBundle;
import org.zipp.ai.domain.retrieval.EvidenceBundleItem;
import org.zipp.ai.domain.retrieval.EvidenceOrigin;
import org.zipp.ai.domain.retrieval.EvidenceProgressListener;
import org.zipp.ai.domain.retrieval.EvidenceSupportRole;
import org.zipp.ai.domain.retrieval.RunResourceDomain;
import org.zipp.ai.domain.retrieval.SourceMode;

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

    public DefaultDirectSourcePreparationModule(VisualObservationModule observations,
                                                ImageToDiagramModule converter) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.converter = Objects.requireNonNull(converter, "converter");
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
        VisualObservationCommand observationCommand = new VisualObservationCommand(
                command.owner(), command.requestId(), command.runId(),
                VisualObservationPurpose.DIAGRAM_RECONSTRUCTION, command.question(),
                List.of(command.target()), 32);
        return observations.observe(observationCommand, resources, signal)
                .thenApply(outcome -> prepareObserved(command, resources, listener, signal, outcome));
    }

    private DirectSourceOutcome prepareObserved(DirectSourceCommand command,
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
            CitationProjection citations = citations(command, graph);
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

    private CitationProjection citations(DirectSourceCommand command, ObservedDiagramGraph graph) {
        List<EvidenceBundleItem> items = new ArrayList<>();
        List<CitationBinding> bindings = new ArrayList<>();
        for (ObservedDiagramGraph.Group group : graph.groups()) {
            addNodeCitation(command, DirectDiagramCellIds.group(group.id()), "group-" + group.id(),
                    group.label(), group.evidenceId(), items, bindings);
        }
        for (ObservedDiagramGraph.Node node : graph.nodes()) {
            addNodeCitation(command, DirectDiagramCellIds.node(node.id()), "node-" + node.id(),
                    node.label(), node.evidenceId(), items, bindings);
        }
        java.util.Map<String, ObservedDiagramGraph.Node> nodes = new java.util.LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        for (ObservedDiagramGraph.Edge edge : graph.edges()) {
            ObservedDiagramGraph.Node source = nodes.get(edge.resolvedSourceId());
            ObservedDiagramGraph.Node target = nodes.get(edge.resolvedTargetId());
            String statement = String.join(" ", List.of(source.label(), edge.label(), target.label()))
                    .replaceAll("\\s+", " ").trim();
            String citationKey = "direct-edge-" + edge.id();
            items.add(item(command, citationKey, edge.evidenceId(), statement));
            bindings.add(new CitationBinding(DirectDiagramCellIds.edge(edge.id()),
                    "direct-edge-statement-" + edge.id(), StatementKind.EDGE_RELATION,
                    statement, DirectDiagramCellIds.node(source.id()),
                    DirectDiagramCellIds.node(target.id()), List.of(citationKey),
                    List.of(new SupportAtom("direct-edge-atom-" + edge.id(), citationKey,
                            statement, SupportAtomRole.RELATION)), SupportType.EVIDENCE));
        }
        EvidenceBundle bundle = new EvidenceBundle("direct-bundle-" + command.runId(),
                command.requestId(), command.runId(), SourceMode.EXPLICIT_ONLY, items);
        return new CitationProjection(EvidenceAccessContext.from(bundle, false), bindings);
    }

    private void addNodeCitation(DirectSourceCommand command, String cellId, String localKey,
                                 String statement, String evidenceId,
                                 List<EvidenceBundleItem> items,
                                 List<CitationBinding> bindings) {
        String citationKey = "direct-" + localKey;
        items.add(item(command, citationKey, evidenceId, statement));
        bindings.add(new CitationBinding(cellId, "direct-statement-" + localKey,
                StatementKind.NODE_TEXT, statement, null, null, List.of(citationKey),
                List.of(new SupportAtom("direct-atom-" + localKey, citationKey,
                        statement, SupportAtomRole.DIRECT_QUOTE)), SupportType.EVIDENCE));
    }

    private EvidenceBundleItem item(DirectSourceCommand command, String citationKey,
                                    String evidenceId, String statement) {
        VisualObservationTarget target = command.target();
        return new EvidenceBundleItem(citationKey, evidenceId, target.materialId(),
                target.versionId(), target.revisionId(), target.sourceLabel(), target.pageNumber(),
                "VISUAL", statement, EvidenceSupportRole.SUPPORT, EvidenceOrigin.EXPLICIT);
    }

    private boolean stopped(RunResourceDomain resources, CancellationSignal signal) {
        return resources.isClosed() || signal.isCancelled() || Thread.currentThread().isInterrupted();
    }

    private record CitationProjection(EvidenceAccessContext access,
                                      List<CitationBinding> bindings) {}
}
