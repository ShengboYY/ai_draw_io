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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Prepares a single image directly. It intentionally has no retrieval or index dependency.
 */
public final class DefaultDirectSourcePreparationModule implements DirectSourcePreparationModule {
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    private final VisualArtifactReaderPort artifacts;
    private final VisionModelPort model;
    private final ImageToDiagramModule converter;
    private final ExecutorService executor;
    private final long timeoutMillis;

    public DefaultDirectSourcePreparationModule(VisualArtifactReaderPort artifacts,
                                                VisionModelPort model,
                                                ImageToDiagramModule converter) {
        this(artifacts, model, converter, ForkJoinPool.commonPool(), 30_000);
    }

    public DefaultDirectSourcePreparationModule(VisualArtifactReaderPort artifacts,
                                                VisionModelPort model,
                                                ImageToDiagramModule converter,
                                                ExecutorService executor,
                                                long timeoutMillis) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.model = Objects.requireNonNull(model, "model");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.executor = Objects.requireNonNull(executor, "executor");
        if (timeoutMillis < 1) throw new IllegalArgumentException("timeoutMillis must be positive");
        this.timeoutMillis = timeoutMillis;
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

        CompletableFuture<DirectSourceOutcome> result = new CompletableFuture<>();
        Future<?> task = executor.submit(() ->
                result.complete(prepareNow(command, resources, listener, signal)));
        // The run owns interruption of the potentially blocking visual provider call.
        resources.attach(() -> task.cancel(true));
        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute(() -> {
            if (result.complete(new DirectSourceOutcome.Unavailable("DIRECT_VISUAL_PROVIDER_UNAVAILABLE"))) {
                task.cancel(true);
            }
        });
        return result;
    }

    private DirectSourceOutcome prepareNow(DirectSourceCommand command, RunResourceDomain resources,
                                           EvidenceProgressListener progress,
                                           CancellationSignal signal) {
        try {
            if (command.target().artifact().byteSize() > MAX_IMAGE_BYTES) {
                return new DirectSourceOutcome.Rejected(List.of("DIRECT_IMAGE_PIXEL_BUDGET_EXCEEDED"));
            }
            byte[] bytes = artifacts.read(command.target().artifact(), MAX_IMAGE_BYTES);
            if (bytes.length > MAX_IMAGE_BYTES) {
                return new DirectSourceOutcome.Rejected(List.of("DIRECT_IMAGE_PIXEL_BUDGET_EXCEEDED"));
            }
            if (stopped(resources, signal)) return new DirectSourceOutcome.Cancelled();
            VisionModelPort.Response response = model.observe(new VisionModelPort.Request(
                    VisualObservationPurpose.DIAGRAM_RECONSTRUCTION, command.question(),
                    List.of(new VisionModelPort.ImageInput(command.target().evidenceId(),
                            command.target().artifact().contentType(), bytes)), 32));
            if (stopped(resources, signal)) return new DirectSourceOutcome.Cancelled();
            progress.onProgress("direct_visual_observation", 1, 1);
            if (!response.gaps().isEmpty()) {
                return new DirectSourceOutcome.NeedsConfirmation(response.gaps());
            }
            ObservedDiagramGraph graph = response.diagramGraph();
            if (graph == null) {
                return new DirectSourceOutcome.Rejected(List.of("NO_DIAGRAM_GRAPH"));
            }
            List<String> invalidAnchors = invalidAnchors(graph, command.target().evidenceId());
            if (!invalidAnchors.isEmpty()) return new DirectSourceOutcome.Rejected(invalidAnchors);
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

    private List<String> invalidAnchors(ObservedDiagramGraph graph, String allowedEvidenceId) {
        List<String> errors = new ArrayList<>();
        graph.nodes().stream()
                .filter(node -> !allowedEvidenceId.equals(node.evidenceId()))
                .map(node -> "INVALID_NODE_EVIDENCE:" + node.id()).forEach(errors::add);
        graph.edges().stream()
                .filter(edge -> !allowedEvidenceId.equals(edge.evidenceId()))
                .map(edge -> "INVALID_EDGE_EVIDENCE:" + edge.id()).forEach(errors::add);
        graph.groups().stream()
                .filter(group -> !allowedEvidenceId.equals(group.evidenceId()))
                .map(group -> "INVALID_GROUP_EVIDENCE:" + group.id()).forEach(errors::add);
        return List.copyOf(errors);
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
            ObservedDiagramGraph.Node observedSource = nodes.get(edge.sourceId());
            ObservedDiagramGraph.Node observedTarget = nodes.get(edge.targetId());
            boolean reverse = edge.direction() == ObservedDiagramGraph.EdgeDirection.REVERSE;
            ObservedDiagramGraph.Node source = reverse ? observedTarget : observedSource;
            ObservedDiagramGraph.Node target = reverse ? observedSource : observedTarget;
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
