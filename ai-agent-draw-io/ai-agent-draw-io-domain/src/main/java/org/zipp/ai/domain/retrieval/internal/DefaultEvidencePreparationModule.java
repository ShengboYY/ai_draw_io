package org.zipp.ai.domain.retrieval.internal;

import org.zipp.ai.domain.retrieval.*;
import org.zipp.ai.domain.retrieval.model.valobj.EmbeddingInputType;
import org.zipp.ai.domain.retrieval.port.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Deep online-retrieval module: policy, readiness, routing, fusion, leases, hydration and bundling. */
public final class DefaultEvidencePreparationModule implements EvidencePreparationModule {
    private static final int RRF_K = 60;
    private static final int FINAL_CANDIDATE_LIMIT = 30;
    private static final int HYDRATE_LIMIT = 16;
    private static final int BUNDLE_ITEM_LIMIT = 8;
    private static final int BUNDLE_CHAR_LIMIT = 24_000;
    private static final long MAX_DISPLAY_BYTES = 256 * 1024L;

    private final EvidenceCatalog catalog;
    private final RetrievalLexicalIndex lexical;
    private final Optional<EmbeddingPort> embedding;
    private final Optional<RetrievalVectorIndex> vectors;
    private final TenantKeyPort tenantKeys;
    private final EvidenceReadLeaseCoordinator leases;
    private final EvidenceBlobStore blobs;
    private final ServerCanvasSnapshotLoader canvases;
    private final DiagramTargetResolver targets = new DiagramTargetResolver();
    private final EvidenceSufficiencyEvaluator sufficiency = new EvidenceSufficiencyEvaluator();
    private final ExecutorService orchestrationExecutor;
    private final ExecutorService ioExecutor;
    private final Duration retrievalTimeout;
    private final Duration hydrationTimeout;
    private final MaterialRetrievalTelemetry telemetry;
    private final CallCircuitBreaker inferenceCircuit = new CallCircuitBreaker(5, Duration.ofSeconds(30));
    private final CallCircuitBreaker vectorCircuit = new CallCircuitBreaker(5, Duration.ofSeconds(30));

    public DefaultEvidencePreparationModule(EvidenceCatalog catalog, RetrievalLexicalIndex lexical,
                                            Optional<EmbeddingPort> embedding,
                                            Optional<RetrievalVectorIndex> vectors,
                                            TenantKeyPort tenantKeys,
                                            EvidenceReadLeaseCoordinator leases, EvidenceBlobStore blobs,
                                            ServerCanvasPort canvases, ExecutorService executor) {
        this(catalog, lexical, embedding, vectors, tenantKeys, leases, blobs, canvases,
                ForkJoinPool.commonPool(), executor,
                Duration.ofSeconds(3), Duration.ofMillis(800), MaterialRetrievalTelemetry.NOOP);
    }

    public DefaultEvidencePreparationModule(EvidenceCatalog catalog, RetrievalLexicalIndex lexical,
                                            Optional<EmbeddingPort> embedding,
                                            Optional<RetrievalVectorIndex> vectors,
                                            TenantKeyPort tenantKeys,
                                            EvidenceReadLeaseCoordinator leases, EvidenceBlobStore blobs,
                                            ServerCanvasPort canvases, ExecutorService orchestrationExecutor,
                                            ExecutorService ioExecutor,
                                            Duration retrievalTimeout, Duration hydrationTimeout) {
        this(catalog, lexical, embedding, vectors, tenantKeys, leases, blobs, canvases,
                orchestrationExecutor, ioExecutor, retrievalTimeout, hydrationTimeout,
                MaterialRetrievalTelemetry.NOOP);
    }

    public DefaultEvidencePreparationModule(EvidenceCatalog catalog, RetrievalLexicalIndex lexical,
                                            Optional<EmbeddingPort> embedding,
                                            Optional<RetrievalVectorIndex> vectors,
                                            TenantKeyPort tenantKeys,
                                            EvidenceReadLeaseCoordinator leases, EvidenceBlobStore blobs,
                                            ServerCanvasPort canvases, ExecutorService orchestrationExecutor,
                                            ExecutorService ioExecutor,
                                            Duration retrievalTimeout, Duration hydrationTimeout,
                                            MaterialRetrievalTelemetry telemetry) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.lexical = Objects.requireNonNull(lexical, "lexical");
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.vectors = Objects.requireNonNull(vectors, "vectors");
        this.tenantKeys = Objects.requireNonNull(tenantKeys, "tenantKeys");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.blobs = Objects.requireNonNull(blobs, "blobs");
        this.canvases = new ServerCanvasSnapshotLoader(Objects.requireNonNull(canvases, "canvases"));
        this.orchestrationExecutor = Objects.requireNonNull(orchestrationExecutor, "orchestrationExecutor");
        this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
        this.retrievalTimeout = positive(retrievalTimeout, "retrievalTimeout");
        this.hydrationTimeout = positive(hydrationTimeout, "hydrationTimeout");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    @Override
    public CompletionStage<PreparationOutcome> prepare(EvidencePreparationCommand command,
                                                       RunResourceDomain resources,
                                                       EvidenceProgressListener progress,
                                                       CancellationSignal cancellation) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resources, "resources");
        EvidenceProgressListener listener = progress == null ? EvidenceProgressListener.NOOP : progress;
        CancellationSignal signal = cancellation == null ? CancellationSignal.NEVER : cancellation;
        long startedNanos = System.nanoTime();
        CompletionStage<PreparationOutcome> outcome = !command.needsEvidence()
                ? CompletableFuture.completedFuture(new PreparationOutcome.NotRequired())
                : CompletableFuture.supplyAsync(
                        () -> prepareNow(command, resources, listener, signal, false), orchestrationExecutor);
        return outcome.whenComplete((result, failure) -> recordTelemetry(
                command, result, failure, System.nanoTime() - startedNanos));
    }

    @Override
    public CompletionStage<Void> observe(EvidencePreparationCommand command) {
        Objects.requireNonNull(command, "command");
        RunResourceDomain resources = new RunResourceDomain();
        long startedNanos = System.nanoTime();
        return CompletableFuture.supplyAsync(() -> prepareNow(command, resources,
                        EvidenceProgressListener.NOOP, CancellationSignal.NEVER, true), orchestrationExecutor)
                .whenComplete((outcome, failure) -> {
                    resources.closeExactlyOnce(failure == null ? CloseReason.COMPLETED : CloseReason.FAILED);
                    recordTelemetry(command, outcome, failure, System.nanoTime() - startedNanos);
                }).thenApply(ignored -> null);
    }

    private void recordTelemetry(EvidencePreparationCommand command, PreparationOutcome outcome,
                                 Throwable failure, long elapsedNanos) {
        String route = "unknown";
        String result = failure == null && outcome != null
                ? outcome.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1_$2") : "failed";
        int evidenceItems = 0;
        if (outcome instanceof PreparationOutcome.Ready ready) {
            route = ready.diagnostics().route().name();
            evidenceItems = ready.preparedEvidence().bundle().items().size();
        } else if (outcome instanceof PreparationOutcome.NotRequired) {
            route = "NONE";
        } else if (outcome instanceof PreparationOutcome.ShadowObserved shadow) {
            route = shadow.diagnostics().route().name();
            result = "shadow_observed";
            try {
                telemetry.recordCandidates(route, command.sourceMode().name(), "authorized",
                        shadow.candidateCount());
            } catch (RuntimeException ignored) {
                // Candidate metrics cannot change observation completion.
            }
        }
        try {
            telemetry.record(route, command.sourceMode().name(), result,
                    Duration.ofNanos(Math.max(0L, elapsedNanos)), evidenceItems);
        } catch (RuntimeException ignored) {
            // Observability is best-effort and must never change a retrieval outcome.
        }
    }

    private PreparationOutcome prepareNow(EvidencePreparationCommand command, RunResourceDomain resources,
                                          EvidenceProgressListener progress, CancellationSignal cancellation,
                                          boolean shadowOnly) {
        if (cancelled(cancellation, resources)) return new PreparationOutcome.Cancelled();
        RetrievalDeadline deadline = RetrievalDeadline.start(retrievalTimeout);
        try {
            if (command.selectedVersionIds().size() > 500) {
                return new PreparationOutcome.InsufficientEvidence(List.of("EXPLICIT_SOURCE_LIMIT_EXCEEDED"));
            }
            TargetResolution target = resolveTarget(command, deadline, cancellation, resources);
            if (target.stop() != null) return target.stop();

            progress.onProgress("SOURCE_POLICY", 0, 1);
            SourceResolution resolution = command.hasResolvedSources()
                    ? command.resolvedSources().toSourceResolution()
                    : callWithinDeadline(() -> catalog.resolveSources(command), deadline, cancellation, resources);
            PreparationOutcome readinessStop = readiness(command, resolution);
            if (readinessStop != null) return readinessStop;
            AuthorizedSourceSet sources = readySources(command, resolution);
            if (sources.sources().isEmpty()) {
                return command.requiresEvidence() || resolution.mode() == SourceMode.EXPLICIT_ONLY
                        ? new PreparationOutcome.InsufficientEvidence(List.of("NO_AUTHORIZED_READY_SOURCE"))
                        : new PreparationOutcome.NotRequired();
            }

            RetrievalRoute route = route(command, sources);
            if (route == RetrievalRoute.NONE) return new PreparationOutcome.NotRequired();
            if (!shadowOnly && route == RetrievalRoute.HYBRID
                    && sources.sources().stream().anyMatch(AuthorizedSource::hasVisual)) {
                // Whole-document/hybrid requests must not silently ignore figures or tables that
                // require pixels. A later configured VisualEvidenceVerifier may satisfy this gap.
                return insufficient(command, "VISUAL_VERIFICATION_REQUIRED");
            }
            List<CandidateRef> existing = target.cellIds().isEmpty() ? List.of() : callWithinDeadline(
                    () -> catalog.existingTargetCandidates(command.diagramId(),
                            command.canvasProbe().serverCanvasVersion(), target.cellIds(), sources, 12),
                    deadline, cancellation, resources);
            Set<String> existingChunkIds = existing.stream().map(CandidateRef::chunkId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!shadowOnly) {
                PreparationOutcome existingOnly = prepareExistingOnly(command, resolution, sources, route,
                        target, existingChunkIds, resources, progress, cancellation, deadline, new ArrayList<>());
                if (existingOnly != null) return existingOnly;
            }
            List<String> queries = planQueries(command.userMessage(), target.labels());
            progress.onProgress("RETRIEVAL", 0, 2);

            List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());
            Future<List<CandidateRef>> lexicalFuture = ioExecutor.submit(
                    () -> safeLexical(queries, sources, route, diagnostics));
            Future<List<CandidateRef>> denseFuture = ioExecutor.submit(
                    () -> safeDense(command, queries, sources, route, diagnostics));
            List<CandidateRef> lexicalCandidates = await(lexicalFuture, deadline,
                    diagnostics, "LEXICAL_TIMEOUT");
            progress.onProgress("RETRIEVAL", 1, 2);
            List<CandidateRef> denseCandidates = await(denseFuture, deadline,
                    diagnostics, "DENSE_TIMEOUT");
            progress.onProgress("RETRIEVAL", 2, 2);
            if (cancelled(cancellation, resources)) return new PreparationOutcome.Cancelled();

            LinkedHashSet<String> rankedIds = new LinkedHashSet<>();
            existing.stream().map(CandidateRef::chunkId).forEach(rankedIds::add);
            fuse(lexicalCandidates, denseCandidates).stream().limit(40)
                    .map(ScoredChunk::chunkId).forEach(rankedIds::add);
            List<String> fusedIds = rankedIds.stream().limit(40).toList();
            if (fusedIds.isEmpty()) return insufficient(command, "NO_RETRIEVAL_MATCH");
            List<AuthorizedCandidate> authorized = callWithinDeadline(
                    () -> catalog.reauthorize(fusedIds, sources, FINAL_CANDIDATE_LIMIT),
                    deadline, cancellation, resources);
            authorized = authorized.stream().filter(candidate -> candidate.qualityScore() >= 0.20).toList();
            if (authorized.isEmpty()) return insufficient(command, "NO_AUTHORIZED_CANDIDATE");
            if (shadowOnly) {
                return new PreparationOutcome.ShadowObserved(
                        new RetrievalDiagnostics(route, List.copyOf(diagnostics)), authorized.size());
            }
            if (authorized.stream().anyMatch(candidate -> "VISUAL".equals(candidate.modality()))) {
                // Online visual verification is a separate capability. Until a verified visual
                // fragment is available, retrieval text/captions cannot support visual facts.
                diagnostics.add("VISUAL_VERIFICATION_UNAVAILABLE");
                if (route == RetrievalRoute.VISUAL || route == RetrievalRoute.VISUAL_EXACT) {
                    return insufficient(command, "VISUAL_VERIFICATION_REQUIRED");
                }
                authorized = authorized.stream()
                        .filter(candidate -> !"VISUAL".equals(candidate.modality())).toList();
                if (authorized.isEmpty()) return insufficient(command, "NO_VERIFIED_DISPLAY_EVIDENCE");
            }

            // Lease only source revisions that survived final MySQL re-authorization. Discovery may
            // inspect a wider set, but unused library material must not gain a read lease.
            Set<String> usedVersions = authorized.stream().map(AuthorizedCandidate::versionId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            AuthorizedSourceSet usedSources = new AuthorizedSourceSet(command.owner(), sources.mode(),
                    sources.sources().stream().filter(source -> usedVersions.contains(source.versionId())).toList());
            callWithinDeadline(() -> {
                AutoCloseable leaseSet = leases.acquire(command.owner(), command.runId(), usedSources);
                // Register in the same continuation that creates the resource. If the caller has
                // already timed out and closed the run, attach immediately releases a late lease.
                resources.attach(leaseSet);
                return null;
            }, deadline, cancellation, resources);
            if (cancelled(cancellation, resources)) return new PreparationOutcome.Cancelled();

            progress.onProgress("HYDRATION", 0, Math.min(HYDRATE_LIMIT, authorized.size()));
            List<EvidenceBundleItem> items = hydrate(authorized, fusedIds, existingChunkIds,
                    !target.cellIds().isEmpty(), Set.copyOf(command.declaredVersionIds()),
                    progress, cancellation, resources, deadline, diagnostics);
            if (items.isEmpty()) {
                resources.closeExactlyOnce(CloseReason.FAILED);
                return insufficient(command, "NO_DISPLAY_EVIDENCE");
            }
            EvidenceSufficiencyEvaluator.Result support = sufficiency.evaluate(command.userMessage(), route, items);
            if (!support.sufficient()) {
                resources.closeExactlyOnce(CloseReason.FAILED);
                return insufficient(command, support.gap());
            }
            EvidenceBundle bundle = new EvidenceBundle(bundleId(command), command.requestId(), command.runId(),
                    resolution.mode(), items);
            resources.markPrepared();
            return new PreparationOutcome.Ready(new PreparedEvidence(bundle, resources, target.targets()),
                    new RetrievalDiagnostics(route, List.copyOf(diagnostics)));
        } catch (RetrievalCancelledException cancelled) {
            resources.closeExactlyOnce(CloseReason.CANCELLED);
            return new PreparationOutcome.Cancelled();
        } catch (Exception exception) {
            resources.closeExactlyOnce(CloseReason.FAILED);
            return new PreparationOutcome.Failed("EVIDENCE_PREPARATION_FAILED");
        }
    }

    private PreparationOutcome prepareExistingOnly(EvidencePreparationCommand command,
                                                    SourceResolution resolution,
                                                    AuthorizedSourceSet sources,
                                                    RetrievalRoute route,
                                                    TargetResolution target,
                                                    Set<String> existingChunkIds,
                                                    RunResourceDomain resources,
                                                    EvidenceProgressListener progress,
                                                    CancellationSignal cancellation,
                                                    RetrievalDeadline deadline,
                                                    List<String> diagnostics) {
        if (existingChunkIds.isEmpty()) return null;
        List<String> rankedIds = List.copyOf(existingChunkIds);
        List<AuthorizedCandidate> authorized = callWithinDeadline(
                () -> catalog.reauthorize(rankedIds, sources, Math.min(FINAL_CANDIDATE_LIMIT, rankedIds.size())),
                deadline, cancellation, resources).stream()
                .filter(candidate -> candidate.qualityScore() >= 0.20)
                // A historical visual citation still needs a current authenticated observation.
                .filter(candidate -> !"VISUAL".equals(candidate.modality())).toList();
        if (authorized.isEmpty()) return null;
        Set<String> versions = authorized.stream().map(AuthorizedCandidate::versionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        AuthorizedSourceSet usedSources = new AuthorizedSourceSet(command.owner(), sources.mode(),
                sources.sources().stream().filter(source -> versions.contains(source.versionId())).toList());
        callWithinDeadline(() -> {
            resources.attach(leases.acquire(command.owner(), command.runId(), usedSources));
            return null;
        }, deadline, cancellation, resources);
        List<EvidenceBundleItem> items = hydrate(authorized, rankedIds, existingChunkIds, true,
                Set.copyOf(command.declaredVersionIds()), progress, cancellation, resources, deadline, diagnostics);
        if (items.isEmpty() || !sufficiency.evaluate(command.userMessage(), route, items).sufficient()) {
            return null;
        }
        EvidenceBundle bundle = new EvidenceBundle(bundleId(command), command.requestId(), command.runId(),
                resolution.mode(), items);
        resources.markPrepared();
        return new PreparationOutcome.Ready(new PreparedEvidence(bundle, resources, target.targets()),
                new RetrievalDiagnostics(route, List.copyOf(diagnostics)));
    }

    private TargetResolution resolveTarget(EvidencePreparationCommand command, RetrievalDeadline deadline,
                                           CancellationSignal cancellation, RunResourceDomain resources) {
        if (!command.requiresTarget() && command.selection().cellIds().isEmpty()) return TargetResolution.none();
        if (!command.selection().cellIds().isEmpty() && command.canvasProbe().selectionVersionMismatch()) {
            return TargetResolution.stop(new PreparationOutcome.StaleCanvasSelection("STALE_CANVAS_SELECTION"));
        }
        ServerCanvasSnapshotLoader.LoadResult loaded = callWithinDeadline(
                () -> canvases.load(command.owner(), command.diagramId(), command.canvasProbe()),
                deadline, cancellation, resources);
        if (loaded instanceof ServerCanvasSnapshotLoader.LoadResult.Unavailable unavailable) {
            return TargetResolution.stop(new PreparationOutcome.CanvasUnavailable(unavailable.errorCode()));
        }
        if (loaded instanceof ServerCanvasSnapshotLoader.LoadResult.Changed changed) {
            return TargetResolution.stop(new PreparationOutcome.CanvasChangedRetry(changed.expectedVersion(), changed.actualVersion()));
        }
        var snapshot = ((ServerCanvasSnapshotLoader.LoadResult.Ready) loaded).snapshot();
        if (!command.selection().cellIds().isEmpty()
                && (command.selection().canvasVersion() == null
                || command.selection().canvasVersion() != snapshot.version()
                || command.selection().contentHash().isBlank()
                || !command.selection().contentHash().equals(snapshot.contentHash()))) {
            return TargetResolution.stop(new PreparationOutcome.StaleCanvasSelection("STALE_CANVAS_SELECTION"));
        }
        DiagramTargetResolver.TargetResult result = targets.resolve(
                snapshot.xml(), command.selection(), command.userMessage());
        if (result instanceof DiagramTargetResolver.TargetResult.Ambiguous ambiguous) {
            return TargetResolution.stop(new PreparationOutcome.TargetClarification(ambiguous.candidates()));
        }
        if (result instanceof DiagramTargetResolver.TargetResult.Missing missing && command.requiresTarget()) {
            return TargetResolution.stop(new PreparationOutcome.TargetClarification(List.of(
                    new TargetCandidate("", "UNKNOWN", "", missing.errorCode()))));
        }
        if (result instanceof DiagramTargetResolver.TargetResult.Resolved resolved) {
            return new TargetResolution(null, resolved.cells().stream()
                    .map(cell -> new EvidenceTarget(cell.id(), cell.kind(), cell.label(),
                            cell.sourceId(), cell.targetId(), cell.nearbyLabels())).toList());
        }
        return TargetResolution.none();
    }

    private PreparationOutcome readiness(EvidencePreparationCommand command, SourceResolution resolution) {
        if (resolution.pendingConversationUploadCount() > 0) {
            return new PreparationOutcome.Waiting(List.of(new MaterialReadiness(
                    "pending-conversation-upload", "PROCESSING", true)));
        }
        if (resolution.unavailableExplicitSourceCount() > 0
                && (command.requiresEvidence() || resolution.mode() == SourceMode.EXPLICIT_ONLY)) {
            return new PreparationOutcome.InsufficientEvidence(List.of("EXPLICIT_SOURCE_UNAVAILABLE"));
        }
        List<AuthorizedSource> notReady = resolution.sources().stream()
                .filter(source -> !"READY".equals(source.state())).filter(AuthorizedSource::required).toList();
        if (notReady.isEmpty()) return null;
        List<MaterialReadiness> states = notReady.stream()
                .map(source -> new MaterialReadiness(source.versionId(), source.state(), source.conversationScoped()))
                .toList();
        boolean waiting = notReady.stream().allMatch(source -> source.conversationScoped()
                && !source.partialReady() && !"FAILED".equals(source.state()));
        return waiting ? new PreparationOutcome.Waiting(states) : new PreparationOutcome.MaterialNotReady(states);
    }

    private AuthorizedSourceSet readySources(EvidencePreparationCommand command, SourceResolution resolution) {
        List<AuthorizedSource> required = resolution.sources().stream()
                .filter(source -> "READY".equals(source.state()) && source.required()).toList();
        List<AuthorizedSource> optional = resolution.sources().stream()
                .filter(source -> "READY".equals(source.state()) && !source.required()).limit(80).toList();
        List<AuthorizedSource> ready = new ArrayList<>(required.size() + optional.size());
        ready.addAll(required);
        ready.addAll(optional);
        return new AuthorizedSourceSet(command.owner(), resolution.mode(), ready);
    }

    private RetrievalRoute route(EvidencePreparationCommand command, AuthorizedSourceSet sources) {
        String value = command.userMessage().toLowerCase(Locale.ROOT);
        if (contains(value, "颜色", "配色", "字体", "移动", "布局", "排版", "color", "font", "layout")) {
            return RetrievalRoute.NONE;
        }
        if (command.declaredVersionIds().size() == 1 && contains(value, "这张图片", "这幅图", "this image")) {
            return RetrievalRoute.VISUAL_EXACT;
        }
        if (contains(value, "图片", "图中", "箭头", "图例", "扫描", "表格布局", "image", "arrow", "legend")) {
            return RetrievalRoute.VISUAL;
        }
        if (contains(value, "整份", "总结", "梳理", "绘图", "流程图", "whole document", "summarize", "diagram")) {
            return RetrievalRoute.HYBRID;
        }
        return RetrievalRoute.TEXT;
    }

    private List<String> planQueries(String message, List<String> targetLabels) {
        String query = message == null ? "" : message.trim();
        String targetContext = targetLabels == null ? "" : targetLabels.stream()
                .map(label -> label.replaceAll("<[^>]+>", " ").trim())
                .filter(label -> !label.isBlank()).limit(4).reduce("", (left, right) -> left + " " + right).trim();
        if (!targetContext.isBlank()) query = (query + " " + targetContext).trim();
        if (query.isBlank()) return List.of("document overview");
        List<String> facets = new ArrayList<>();
        facets.add(query);
        for (String part : query.split("[，。；;!?！？]")) {
            String trimmed = part.trim();
            if (trimmed.length() >= 4 && !facets.contains(trimmed)) facets.add(trimmed);
            if (facets.size() == 3) break;
        }
        return List.copyOf(facets);
    }

    private List<CandidateRef> safeLexical(List<String> queries, AuthorizedSourceSet sources,
                                           RetrievalRoute route, List<String> diagnostics) {
        try {
            return lexical.search(queries, sources, route, 40);
        } catch (Exception exception) {
            diagnostics.add("LEXICAL_DEGRADED");
            return List.of();
        }
    }

    private List<CandidateRef> safeDense(EvidencePreparationCommand command, List<String> queries,
                                         AuthorizedSourceSet sources, RetrievalRoute route,
                                         List<String> diagnostics) {
        if (embedding.isEmpty() || vectors.isEmpty()) {
            diagnostics.add("DENSE_UNAVAILABLE");
            return List.of();
        }
        if (!inferenceCircuit.tryAcquire()) {
            diagnostics.add("DENSE_INFERENCE_CIRCUIT_OPEN");
            return List.of();
        }
        List<float[]> queryVectors;
        try {
            queryVectors = embedding.get().embed(queries, EmbeddingInputType.QUERY);
            inferenceCircuit.recordSuccess();
        } catch (Exception exception) {
            inferenceCircuit.recordFailure();
            diagnostics.add("DENSE_INFERENCE_DEGRADED");
            return List.of();
        }
        if (!vectorCircuit.tryAcquire()) {
            diagnostics.add("DENSE_INDEX_CIRCUIT_OPEN");
            return List.of();
        }
        try {
            List<String> vectorIds = new ArrayList<>();
            String tenant = tenantKeys.opaqueKey(command.owner().ownerType(), command.owner().ownerKey());
            List<String> versions = sources.sources().stream().map(AuthorizedSource::versionId).distinct().toList();
            for (float[] vector : queryVectors) {
                // Keep provider filters bounded while ensuring every authorized explicit version can
                // compete before top-K truncation; MySQL still re-authorizes all returned IDs.
                for (int start = 0; start < versions.size(); start += 80) {
                    vectorIds.addAll(vectors.get().query(vector, tenant,
                            versions.subList(start, Math.min(start + 80, versions.size())), 40));
                }
            }
            vectorCircuit.recordSuccess();
            try {
                return catalog.resolveVectorCandidates(vectorIds.stream().distinct().toList(), sources);
            } catch (Exception mappingFailure) {
                diagnostics.add("DENSE_ID_MAPPING_DEGRADED");
                return List.of();
            }
        } catch (Exception exception) {
            vectorCircuit.recordFailure();
            diagnostics.add("DENSE_DEGRADED");
            return List.of();
        }
    }

    private List<ScoredChunk> fuse(List<CandidateRef> lexicalCandidates, List<CandidateRef> denseCandidates) {
        Map<String, Double> scores = new HashMap<>();
        addLane(scores, lexicalCandidates, 1.2);
        addLane(scores, denseCandidates, 1.0);
        return scores.entrySet().stream().map(entry -> new ScoredChunk(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(ScoredChunk::chunkId)).toList();
    }

    private void addLane(Map<String, Double> scores, List<CandidateRef> candidates, double weight) {
        for (int index = 0; index < candidates.size(); index++) {
            CandidateRef candidate = candidates.get(index);
            scores.merge(candidate.chunkId(), weight / (RRF_K + index + 1), Double::sum);
        }
    }

    private List<EvidenceBundleItem> hydrate(List<AuthorizedCandidate> authorized, List<String> fusedIds,
                                             Set<String> existingChunkIds, boolean hasTarget,
                                             Set<String> explicitlySelectedVersions,
                                             EvidenceProgressListener progress, CancellationSignal cancellation,
                                             RunResourceDomain resources, RetrievalDeadline deadline,
                                             List<String> diagnostics) {
        Map<String, Integer> rank = new HashMap<>();
        for (int index = 0; index < fusedIds.size(); index++) rank.put(fusedIds.get(index), index);
        List<AuthorizedCandidate> ranked = authorized.stream()
                .sorted(Comparator.comparingInt((AuthorizedCandidate candidate) ->
                                rank.getOrDefault(candidate.chunkId(), Integer.MAX_VALUE))
                        .thenComparing(Comparator.comparingDouble(AuthorizedCandidate::qualityScore).reversed()))
                .limit(HYDRATE_LIMIT).toList();
        List<EvidenceBundleItem> items = new ArrayList<>();
        Set<String> evidenceIds = new HashSet<>();
        Map<String, Integer> materialCounts = new HashMap<>();
        int visualCount = 0;
        int chars = 0;
        for (int index = 0; index < ranked.size() && items.size() < BUNDLE_ITEM_LIMIT; index++) {
            if (cancelled(cancellation, resources)) break;
            AuthorizedCandidate candidate = ranked.get(index);
            if (!evidenceIds.add(candidate.evidenceId())) continue;
            if (materialCounts.getOrDefault(candidate.materialId(), 0) >= 3) continue;
            if ("VISUAL".equals(candidate.modality()) && visualCount >= 3) continue;
            String text;
            Future<String> read = null;
            try {
                read = ioExecutor.submit(() -> blobs.readDisplayText(candidate, MAX_DISPLAY_BYTES));
                long remaining = deadline.boundedNanos(hydrationTimeout);
                if (remaining <= 0L) {
                    read.cancel(true);
                    diagnostics.add("S3_EVIDENCE_TIMEOUT");
                    break;
                }
                text = read.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                if (read != null) read.cancel(true);
                diagnostics.add("S3_EVIDENCE_TIMEOUT");
                continue;
            } catch (InterruptedException interrupted) {
                if (read != null) read.cancel(true);
                Thread.currentThread().interrupt();
                diagnostics.add("S3_EVIDENCE_CANCELLED");
                break;
            } catch (ExecutionException | RuntimeException unavailableObject) {
                // A missing immutable object only invalidates this candidate; stable backfill may
                // still produce a sufficient bundle from the remaining authorized candidates.
                diagnostics.add("S3_EVIDENCE_DEGRADED");
                continue;
            }
            if (text == null || text.isBlank()) continue;
            int remaining = BUNDLE_CHAR_LIMIT - chars;
            if (remaining <= 0) break;
            String bounded = text.length() <= remaining ? text : text.substring(0, remaining);
            EvidenceOrigin origin = existingChunkIds.contains(candidate.chunkId())
                    ? EvidenceOrigin.EXISTING_REFERENCE
                    : explicitlySelectedVersions.contains(candidate.versionId()) ? EvidenceOrigin.EXPLICIT
                    : hasTarget ? EvidenceOrigin.SUPPLEMENTAL : EvidenceOrigin.SEARCH;
            items.add(new EvidenceBundleItem("cite_" + (items.size() + 1), candidate.evidenceId(),
                    candidate.materialId(), candidate.versionId(), candidate.revisionId(),
                    candidate.sourceLabel(), candidate.pageNumber(),
                    candidate.modality(), bounded, EvidenceSupportRole.SUPPORT, origin));
            chars += bounded.length();
            materialCounts.merge(candidate.materialId(), 1, Integer::sum);
            if ("VISUAL".equals(candidate.modality())) visualCount++;
            progress.onProgress("HYDRATION", index + 1, ranked.size());
        }
        return List.copyOf(items);
    }

    private PreparationOutcome insufficient(EvidencePreparationCommand command, String gap) {
        return new PreparationOutcome.InsufficientEvidence(List.of(gap));
    }

    private List<CandidateRef> await(Future<List<CandidateRef>> future, RetrievalDeadline deadline,
                                     List<String> diagnostics, String timeoutCode) {
        long remaining = deadline.remainingNanos();
        if (remaining <= 0L) {
            future.cancel(true);
            diagnostics.add(timeoutCode);
            return List.of();
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            diagnostics.add(timeoutCode);
            return List.of();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            diagnostics.add("RETRIEVAL_CANCELLED");
            return List.of();
        } catch (ExecutionException failed) {
            diagnostics.add(timeoutCode.replace("TIMEOUT", "FAILED"));
            return List.of();
        }
    }

    private <T> T callWithinDeadline(Callable<T> call, RetrievalDeadline deadline,
                                     CancellationSignal cancellation, RunResourceDomain resources) {
        Future<T> future = ioExecutor.submit(call);
        try {
            while (true) {
                if (cancelled(cancellation, resources)) {
                    future.cancel(true);
                    throw new RetrievalCancelledException();
                }
                long remaining = deadline.remainingNanos();
                if (remaining <= 0L) {
                    future.cancel(true);
                    throw new RetrievalDeadlineExceededException();
                }
                try {
                    // Polling preserves prompt cancellation while the same monotonic deadline
                    // bounds every database, canvas and lease call.
                    return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)),
                            TimeUnit.NANOSECONDS);
                } catch (TimeoutException pollingTimeout) {
                    if (deadline.remainingNanos() <= 0L) {
                        future.cancel(true);
                        throw new RetrievalDeadlineExceededException();
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RetrievalCancelledException();
        } catch (ExecutionException failed) {
            throw new IllegalStateException("ONLINE_RETRIEVAL_DEPENDENCY_FAILED", failed.getCause());
        }
    }

    private Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private boolean cancelled(CancellationSignal cancellation, RunResourceDomain resources) {
        if (!cancellation.isCancelled() && !resources.isClosed()) return false;
        resources.closeExactlyOnce(CloseReason.CANCELLED);
        return true;
    }

    private boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private String bundleId(EvidencePreparationCommand command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((command.requestId() + ":" + command.runId()).getBytes(StandardCharsets.UTF_8));
            return "eb_" + java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record ScoredChunk(String chunkId, double score) { }

    private record TargetResolution(PreparationOutcome stop, List<EvidenceTarget> targets) {
        private TargetResolution {
            targets = List.copyOf(targets == null ? List.of() : targets);
        }
        List<String> cellIds() { return targets.stream().map(EvidenceTarget::cellId).toList(); }
        List<String> labels() { return targets.stream()
                .flatMap(target -> java.util.stream.Stream.concat(java.util.stream.Stream.of(target.label()),
                        target.nearbyLabels().stream()))
                .filter(label -> label != null && !label.isBlank()).distinct().limit(8).toList(); }
        static TargetResolution none() { return new TargetResolution(null, List.of()); }
        static TargetResolution stop(PreparationOutcome stop) { return new TargetResolution(stop, List.of()); }
    }

    private static final class RetrievalDeadlineExceededException extends RuntimeException { }
    private static final class RetrievalCancelledException extends RuntimeException { }
}
