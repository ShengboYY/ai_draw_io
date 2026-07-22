package org.zipp.ai.ingestion.worker.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPage;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceManifest;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceSourcePage;
import org.zipp.ai.domain.ingestion.model.valobj.ParsedDocument;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.VisualCropManifest;
import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;
import org.zipp.ai.domain.ingestion.service.EvidenceUnitBuilder;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalIndexMode;
import org.zipp.ai.domain.retrieval.projection.RetrievalChunkBuilder;
import org.zipp.ai.domain.retrieval.projection.RetrievalChunkProjection;
import org.zipp.ai.domain.retrieval.projection.RetrievalProjectionManifest;
import org.zipp.ai.domain.retrieval.projection.RetrievalTokenCounter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorClient;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorRecord;
import org.zipp.ai.ingestion.worker.document.PdfBoxDocumentParser;
import org.zipp.ai.ingestion.worker.document.MultilingualE5TokenCounter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Project-pipeline experiment: PDFBox -> canonical Evidence -> project chunks -> Pinecone recall. */
class ControlledPdfDenseRecallLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RetrievalTokenCounter RESEARCH_COUNTER = researchTokenCounter();
    private static final Set<String> CANONICAL_MODES = Set.of("e0-v4", "e1-v5");

    @TempDir
    Path temporaryDirectory;

    @Test
    void generatedCorpusShouldExposeNativeAndOcrBoundariesBeforeRetrieval() throws Exception {
        Path root = researchRoot();
        ProjectionSet projections = buildControlledProjections(root);
        assertFalse(projections.bySourceVersion().values().stream()
                .flatMap(value -> value.chunks().stream()).toList().isEmpty());

        List<ResearchAnchor> anchors = anchors(root).stream()
                .filter(anchor -> Set.of("text", "table").contains(anchor.modality()))
                .filter(anchor -> projections.bySourceVersion().containsKey(anchor.sourceVersion()))
                .toList();
        List<String> unmappable = anchors.stream().filter(anchor -> goldChunkIds(
                projections.bySourceVersion().get(anchor.sourceVersion()), anchor).isEmpty())
                .map(ResearchAnchor::anchorId).toList();
        // These authored rules are intentionally kept in the denominator as parse/chunk misses.
        assertEquals(Set.of(
                "ota-layout-retrieve", "ota-rollback-trigger", "ccr-explicit-source", "ccr-layout",
                "forest-version-rule", "daa-version-superseded", "dcc-layout-retrieve",
                "dcc-explicit-source", "dcc-clarify", "mso-layout-retrieve", "pre-layout",
                "mgv-broader", "mgv-zero", "mgv-cross-user", "mgv-removed"),
                Set.copyOf(unmappable), "Unexpected gold-anchor mappability drift");

        assertImageOnlyPdfRequiresOcr(root, "scanned-operations-cards.pdf", "scanned", 3);
        assertImageOnlyPdfRequiresOcr(root, "realistic-metro-rail-inspection-scan-v1.pdf",
                "rail-scan", 6);
        assertImageOnlyPdfRequiresOcr(root, "drawio-planning-workshop-scan-v1.pdf",
                "drawio-workshop-scan", 6);
    }

    @Test
    void completeEvidenceRankShouldHonorAnyAndAllPartsGroups() {
        List<String> matches = List.of("vector-a", "distractor", "vector-b");
        Map<String, Set<String>> vectorsByAnchor = Map.of(
                "alternative-a", Set.of("vector-a"),
                "alternative-low-grade", Set.of("vector-a"),
                "alternative-missing", Set.of("missing"),
                "part-a", Set.of("vector-a"),
                "part-b", Set.of("vector-b"));
        List<RequiredEvidenceGroup> groups = List.of(
                new RequiredEvidenceGroup("alternative", EvidenceGroupOperator.ANY, List.of(
                        new EvidenceRequirement("alternative-low-grade", 2, 3),
                        new EvidenceRequirement("alternative-missing", 3, 3),
                        new EvidenceRequirement("alternative-a", 3, 3))),
                new RequiredEvidenceGroup("comparison", EvidenceGroupOperator.ALL_PARTS, List.of(
                        new EvidenceRequirement("part-a", 2, 2),
                        new EvidenceRequirement("part-b", 2, 2))));

        assertEquals(3, completeEvidenceRank(matches, groups, vectorsByAnchor));
        assertEquals(0, completeEvidenceRank(matches, List.of(new RequiredEvidenceGroup(
                "below-grade", EvidenceGroupOperator.ANY,
                List.of(new EvidenceRequirement("alternative-low-grade", 2, 3)))), vectorsByAnchor));
    }

    @Test
    void shouldMeasureDenseRecallAfterTheRealPdfAndChunkPipeline() throws Exception {
        String apiKey = System.getenv("PINECONE_API_KEY");
        String indexHost = System.getenv("PINECONE_INDEX_HOST");
        String namespace = System.getenv().getOrDefault("PINECONE_NAMESPACE", "recall-test");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank()
                && indexHost != null && !indexHost.isBlank());
        Assumptions.assumeTrue(namespace.toLowerCase(Locale.ROOT).contains("test")
                || namespace.toLowerCase(Locale.ROOT).contains("dev"));

        Path root = researchRoot();
        String canonicalMode = canonicalMode();
        ProjectionSet projections = buildControlledProjections(root);
        Map<String, ResearchAnchor> anchors = new HashMap<>();
        anchors(root).forEach(anchor -> anchors.put(anchor.anchorId(), anchor));
        String researchSplit = System.getenv().getOrDefault("MATERIAL_RAG_RESEARCH_SPLIT", "development");
        assertTrue(Set.of("development", "validation", "holdout").contains(researchSplit),
                "Unknown research split: " + researchSplit);
        List<ResearchCase> cases = cases(root).stream()
                .filter(ResearchCase::answerable)
                .filter(value -> researchSplit.equals(value.split()))
                .filter(value -> !value.goldAnchorIds().isEmpty()
                        && value.goldAnchorIds().stream().map(anchors::get).allMatch(anchor ->
                        anchor != null && Set.of("text", "table").contains(anchor.modality())))
                .toList();
        assertFalse(cases.isEmpty());

        PineconeVectorClient client = new PineconeVectorClient(
                apiKey, indexHost, "multilingual-e5-large", 1024, JSON);
        ExperimentResult result = runDenseExperiment(
                "controlled", client, namespace, projections, cases, anchors);
        report("Controlled PDF", result);
        writeRawResultIfRequested(root, researchSplit, canonicalMode, result);
        if (Boolean.parseBoolean(System.getenv().getOrDefault("MATERIAL_RAG_ENFORCE_GATES", "true"))) {
            assertTrue(result.metrics().recallAt10() >= 0.90,
                    "Dense PDF Recall@10 fell below the text gate");
            assertTrue(result.metrics().recallAt40() >= 0.95,
                    "Dense PDF candidate Recall@40 fell below the gate");
        }
    }

    @Test
    void shouldMeasurePinnedOpenPdfDenseRecall() throws Exception {
        String apiKey = System.getenv("PINECONE_API_KEY");
        String indexHost = System.getenv("PINECONE_INDEX_HOST");
        String namespace = System.getenv().getOrDefault("PINECONE_NAMESPACE", "recall-test");
        String sourceDirectory = System.getenv("MATERIAL_RAG_OPEN_SOURCE_DIR");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank()
                && indexHost != null && !indexHost.isBlank()
                && sourceDirectory != null && !sourceDirectory.isBlank());
        Assumptions.assumeTrue(namespace.toLowerCase(Locale.ROOT).contains("test")
                || namespace.toLowerCase(Locale.ROOT).contains("dev"));

        Path openRoot = Path.of(sourceDirectory).toAbsolutePath().normalize();
        Map<String, RetrievalProjectionManifest> bySource = new LinkedHashMap<>();
        bySource.put("scrum-guide-2020-en:pinned", buildProjectionFromPath(
                openRoot.resolve("scrum-guide-2020-en.pdf"), "scrum-guide-2020-en", "pinned"));
        bySource.put("nist-ai-rmf-1.0:pinned", buildProjectionFromPath(
                openRoot.resolve("nist-ai-rmf-1.0.pdf"), "nist-ai-rmf-1.0", "pinned"));
        ProjectionSet projections = new ProjectionSet(Map.copyOf(bySource));
        Map<String, ResearchAnchor> anchors = new LinkedHashMap<>();
        List<ResearchCase> cases = new ArrayList<>();
        for (OpenCase value : openCases(researchRoot()).stream()
                .filter(candidate -> bySource.containsKey(candidate.sourceId() + ":pinned")).toList()) {
            anchors.put(value.caseId(), new ResearchAnchor(value.caseId(), value.sourceId(), "pinned",
                    "open_pdf", value.goldMatch(), value.page(), true));
            cases.add(new ResearchCase(value.caseId(), "open_pdf", "openPdf", value.language(),
                    value.query(), "open_diagnostic", true, List.of(value.caseId()),
                    List.of(new RequiredEvidenceGroup(
                    "answer", EvidenceGroupOperator.ANY,
                    List.of(new EvidenceRequirement(value.caseId(), 3, 3))))));
        }
        PineconeVectorClient client = new PineconeVectorClient(
                apiKey, indexHost, "multilingual-e5-large", 1024, JSON);
        ExperimentResult result = runDenseExperiment(
                "open", client, namespace, projections, List.copyOf(cases), Map.copyOf(anchors));
        report("Open PDF", result);
        assertTrue(result.metrics().recallAt10() >= 0.85, "Open PDF Recall@10 fell below research baseline");
        assertTrue(result.metrics().recallAt40() >= 0.95, "Open PDF Recall@40 fell below research baseline");
    }

    @Test
    void shouldDescribePinnedOpenPdfChunkingWithoutCallingPinecone() throws Exception {
        String sourceDirectory = System.getenv("MATERIAL_RAG_OPEN_SOURCE_DIR");
        Assumptions.assumeTrue(sourceDirectory != null && !sourceDirectory.isBlank());
        Path openRoot = Path.of(sourceDirectory).toAbsolutePath().normalize();
        Map<String, RetrievalProjectionManifest> projections = new LinkedHashMap<>();
        projections.put("scrum-guide-2020-en:pinned", buildProjectionFromPath(
                openRoot.resolve("scrum-guide-2020-en.pdf"), "scrum-guide-2020-en", "pinned"));
        projections.put("nist-ai-rmf-1.0:pinned", buildProjectionFromPath(
                openRoot.resolve("nist-ai-rmf-1.0.pdf"), "nist-ai-rmf-1.0", "pinned"));
        List<OpenCase> cases = openCases(researchRoot()).stream()
                .filter(value -> projections.containsKey(value.sourceId() + ":pinned")).toList();

        projections.forEach((sourceVersion, manifest) -> {
            List<RetrievalChunkProjection> searchable = manifest.chunks().stream()
                    .filter(chunk -> chunk.indexMode() == RetrievalIndexMode.DENSE_AND_LEXICAL)
                    .toList();
            List<Integer> tokenCounts = searchable.stream()
                    .map(chunk -> RESEARCH_COUNTER.count(chunk.retrievalText())).sorted().toList();
            Map<String, Long> typeCounts = searchable.stream().collect(java.util.stream.Collectors.groupingBy(
                    chunk -> chunk.chunkType().name(), java.util.TreeMap::new,
                    java.util.stream.Collectors.counting()));
            List<OpenCase> sourceCases = cases.stream()
                    .filter(value -> sourceVersion.equals(value.sourceId() + ":pinned")).toList();
            long exactMapped = sourceCases.stream()
                    .filter(value -> !exactGoldChunkIds(manifest, value.goldMatch()).isEmpty()).count();
            long relevanceMapped = sourceCases.stream().filter(value -> !goldChunkIds(manifest,
                    new ResearchAnchor(value.caseId(), value.sourceId(), "pinned", "open_pdf",
                            value.goldMatch(), value.page(), true)).isEmpty()).count();
            System.out.printf(Locale.ROOT,
                    "Open chunk profile %s chunks=%d tokens[p50=%d,p95=%d,max=%d] "
                            + "exactGoldMapped=%d/%d relevanceMapped=%d/%d%n",
                    sourceVersion, tokenCounts.size(), percentile(tokenCounts, 0.50),
                    percentile(tokenCounts, 0.95), tokenCounts.get(tokenCounts.size() - 1),
                    exactMapped, sourceCases.size(), relevanceMapped, sourceCases.size());
            System.out.println("Open chunk types " + sourceVersion + " " + typeCounts);
        });
    }

    @Test
    void shouldCleanInterruptedResearchRunByExplicitPrefix() throws Exception {
        String apiKey = System.getenv("PINECONE_API_KEY");
        String indexHost = System.getenv("PINECONE_INDEX_HOST");
        String namespace = System.getenv().getOrDefault("PINECONE_NAMESPACE", "recall-test");
        String cleanupPrefix = System.getenv("MATERIAL_RAG_RESEARCH_CLEANUP_PREFIX");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank()
                && indexHost != null && !indexHost.isBlank()
                && cleanupPrefix != null && !cleanupPrefix.isBlank());
        Assumptions.assumeTrue(cleanupPrefix.matches("(?:open|controlled)pdfresearch_[a-z0-9_]+")
                || "openpdfresearch_".equals(cleanupPrefix));
        PineconeVectorClient client = new PineconeVectorClient(
                apiKey, indexHost, "multilingual-e5-large", 1024, JSON);
        List<String> ids = new ArrayList<>();
        String token = null;
        do {
            var page = client.listVectorIds(namespace, token, 100);
            ids.addAll(page.vectorIds().stream().filter(value -> value.startsWith(cleanupPrefix)).toList());
            token = page.nextToken();
        } while (token != null);
        for (int start = 0; start < ids.size(); start += 100) {
            client.delete(namespace, ids.subList(start, Math.min(start + 100, ids.size())));
        }
        waitUntilDeleted(client, namespace, ids);
        System.out.println("Removed interrupted research vectors: " + ids.size());
    }

    private ExperimentResult runDenseExperiment(String prefix, PineconeVectorClient client, String namespace,
                                                ProjectionSet projections, List<ResearchCase> cases,
                                                Map<String, ResearchAnchor> anchors) throws Exception {
        String runId = prefix + "pdfresearch_" + UUID.randomUUID().toString().replace("-", "");
        String tenantKey = runId + "_tenant";
        List<IndexedChunk> indexed = indexedChunks(runId, projections);
        List<String> vectorIds = indexed.stream().map(IndexedChunk::vectorId).toList();
        try {
            List<float[]> passageVectors = embedPassages(client,
                    indexed.stream().map(value -> value.chunk().retrievalText()).toList());
            List<PineconeVectorRecord> records = new ArrayList<>();
            for (int index = 0; index < indexed.size(); index++) {
                IndexedChunk value = indexed.get(index);
                records.add(new PineconeVectorRecord(value.vectorId(), passageVectors.get(index),
                        metadata(tenantKey, value)));
            }
            // Keep live experiments within Pinecone's request-size limits for long PDFs.
            for (int start = 0; start < records.size(); start += 100) {
                client.upsert(namespace, records.subList(start, Math.min(start + 100, records.size())));
            }
            waitUntilSearchable(client, namespace, tenantKey, indexed, passageVectors);
            return new ExperimentResult(runId, canonicalAssembler().fingerprint(), evaluate(
                    client, namespace, tenantKey, cases, anchors, projections, indexed), indexed.size());
        } finally {
            // Pinecone limits delete-by-id payloads, so large open PDFs must be cleaned in batches.
            for (int start = 0; start < vectorIds.size(); start += 100) {
                client.delete(namespace, vectorIds.subList(start, Math.min(start + 100, vectorIds.size())));
            }
            waitUntilDeleted(client, namespace, vectorIds);
        }
    }

    private void report(String label, ExperimentResult result) {
        DenseMetrics metrics = result.metrics();
        System.out.printf(Locale.ROOT,
                "%s dense pipeline: Mapping=%.4f Recall@1=%.4f Recall@5=%.4f Recall@10=%.4f "
                        + "Recall@40=%.4f MRR@10=%.4f chunks=%d%n",
                label, metrics.mappingRate(), metrics.recallAt1(), metrics.recallAt5(),
                metrics.recallAt10(),
                metrics.recallAt40(), metrics.mrrAt10(), result.chunkCount());
        System.out.printf(Locale.ROOT,
                "%s conditional-on-mapping: n=%d Recall@10=%.4f Recall@40=%.4f MRR@10=%.4f%n",
                label, metrics.mappableCaseCount(), metrics.conditional().recallAt10(),
                metrics.conditional().recallAt40(), metrics.conditional().mrrAt10());
        if (!metrics.misses().isEmpty()) System.out.println(label + " dense misses: " + metrics.misses());
        metrics.slices().forEach(slice -> System.out.printf(Locale.ROOT,
                "%s slice %-24s n=%d R@1=%.4f R@5=%.4f R@10=%.4f MRR@10=%.4f%n",
                label, slice.label(), slice.count(), slice.recallAt1(), slice.recallAt5(),
                slice.recallAt10(), slice.mrrAt10()));
        if (!metrics.weakCases().isEmpty()) {
            System.out.println(label + " cases outside top 5: " + metrics.weakCases());
        }
    }

    private void writeRawResultIfRequested(Path root, String split, String canonicalMode,
                                           ExperimentResult result) throws Exception {
        String configured = System.getenv("MATERIAL_RAG_RESULT_JSON");
        if (configured == null || configured.isBlank()) return;
        Path output = Path.of(configured).toAbsolutePath().normalize();
        Path lock = root.resolve("fixtures/generated/corpus-lock.json");
        if (!Files.exists(lock)) {
            lock = root.resolve("fixtures/generated/corpus-lock.candidate.json");
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("schemaVersion", "material-rag-dense-run-v1");
        raw.put("runId", result.runId());
        raw.put("gitCommit", System.getenv().getOrDefault("MATERIAL_RAG_COMMIT_SHA", "unknown"));
        raw.put("corpusLockSha256", sha256(lock));
        raw.put("split", split);
        raw.put("canonicalMode", canonicalMode);
        raw.put("canonicalFingerprint", result.canonicalFingerprint());
        raw.put("embeddingModel", "multilingual-e5-large");
        raw.put("tokenizerFingerprint", RESEARCH_COUNTER.fingerprint());
        raw.put("candidateLimit", 40);
        raw.put("chunkCount", result.chunkCount());
        raw.put("metrics", result.metrics());
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), raw);
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private ProjectionSet buildControlledProjections(Path root) throws Exception {
        Map<String, RetrievalProjectionManifest> result = new LinkedHashMap<>();
        result.put("controlled-guide-v1:v1", buildProjection(root,
                "controlled-guide-v1", "v1", "controlled-operations-guide-v1.pdf"));
        result.put("controlled-guide-v2:v2", buildProjection(root,
                "controlled-guide-v2", "v2", "controlled-operations-guide-v2.pdf"));
        result.put("realistic-harbor-report:v1", buildProjection(root,
                "realistic-harbor-report", "v1", "realistic-harbor-grid-report-v1.pdf"));
        result.put("realistic-water-audit:v1", buildProjection(root,
                "realistic-water-audit", "v1", "realistic-coastal-water-audit-v1.pdf"));
        result.put("realistic-solar-manual:v1", buildProjection(root,
                "realistic-solar-manual", "v1", "realistic-helios-inverter-manual-v1.pdf"));
        result.put("realistic-cold-chain-report:v1", buildProjection(root,
                "realistic-cold-chain-report", "v1", "realistic-aurora-cold-chain-validation-v1.pdf"));
        result.put("realistic-forest-study:v1", buildProjection(root,
                "realistic-forest-study", "v1", "realistic-meridian-forest-study-v1.pdf"));
        result.put("realistic-museum-condition-memo:v1", buildProjection(root,
                "realistic-museum-condition-memo", "v1", "realistic-arcadia-condition-memo-v1.pdf"));
        result.put("drawio-agent-architecture:v1", buildProjection(root,
                "drawio-agent-architecture", "v1", "drawio-agent-architecture-blueprint-v1.pdf"));
        result.put("drawio-workflow-handbook:v1", buildProjection(root,
                "drawio-workflow-handbook", "v1", "drawio-diagram-workflow-handbook-v1.pdf"));
        result.put("drawio-collaboration-governance:v1", buildProjection(root,
                "drawio-collaboration-governance", "v1", "drawio-collaboration-governance-v1.pdf"));
        result.put("drawio-recovery-runbook:v1", buildProjection(root,
                "drawio-recovery-runbook", "v1", "drawio-agent-recovery-runbook-v1.pdf"));
        result.put("scenario-payment-settlement:v1", buildProjection(root,
                "scenario-payment-settlement", "v1", "scenario-crossborder-payment-settlement-v1.pdf"));
        result.put("scenario-ota-rollout:v1", buildProjection(root,
                "scenario-ota-rollout", "v1", "scenario-vehicle-ota-rollout-v1.pdf"));
        result.put("scenario-gmp-change-control:v1", buildProjection(root,
                "scenario-gmp-change-control", "v1", "scenario-gmp-batch-change-control-v1.pdf"));
        result.put("expansion-datacenter-change:v1", buildProjection(root,
                "expansion-datacenter-change", "v1", "expansion-datacenter-capacity-change-v1.pdf"));
        result.put("expansion-observability:v1", buildProjection(root,
                "expansion-observability", "v1", "expansion-microservice-observability-v1.pdf"));
        result.put("expansion-supply-chain:v1", buildProjection(root,
                "expansion-supply-chain", "v1", "expansion-supply-chain-fulfillment-v1.pdf"));
        result.put("expansion-platform-resilience:v1", buildProjection(root,
                "expansion-platform-resilience", "v1", "expansion-platform-resilience-v1.pdf"));
        result.put("expansion-material-governance:v1", buildProjection(root,
                "expansion-material-governance", "v1", "expansion-material-governance-v1.pdf"));
        return new ProjectionSet(Map.copyOf(result));
    }

    private void assertImageOnlyPdfRequiresOcr(Path root, String filename, String temporaryName,
                                               int expectedPages) throws Exception {
        ParsedDocument scanned = new PdfBoxDocumentParser(150).parse(
                root.resolve("fixtures/generated/pdfs").resolve(filename),
                "application/pdf", temporaryDirectory.resolve(temporaryName));
        OcrSelectionPolicy policy = new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03);
        assertEquals(expectedPages, scanned.pageCount());
        assertTrue(scanned.pages().stream().allMatch(page -> page.extraction().nativeBlocks().isEmpty()));
        assertTrue(scanned.pages().stream().allMatch(page -> policy.requiresOcr("application/pdf",
                page.extraction().nativeTextQuality(), page.extraction().rasterRegions())));
    }

    private RetrievalProjectionManifest buildProjection(Path root, String source, String version,
                                                         String filename) throws Exception {
        return buildProjectionFromPath(root.resolve("fixtures/generated/pdfs").resolve(filename),
                source, version);
    }

    private RetrievalProjectionManifest buildProjectionFromPath(Path pdf, String source,
                                                                 String version) throws Exception {
        String revisionId = "revision-" + source;
        ParsedDocument parsed = new PdfBoxDocumentParser(150).parse(pdf, "application/pdf",
                temporaryDirectory.resolve(source));
        CanonicalPageAssembler assembler = canonicalAssembler();
        List<CanonicalPage> pages = parsed.pages().stream()
                .map(page -> assembler.assemble(page.extraction())).toList();
        var structure = new DocumentStructureBuilder().build(pages);
        List<EvidenceSourcePage> sources = new ArrayList<>();
        for (CanonicalPage page : pages) {
            StoredArtifact artifact = new StoredArtifact("research/" + source + "/page-" + page.pageNo(),
                    "object-version-1", "a".repeat(64), 1, "application/json+gzip");
            sources.add(new EvidenceSourcePage(source + "-page-" + page.pageNo(), page, artifact));
        }
        VisualCropManifest visuals = new VisualCropManifest("visual-crop-manifest-v1",
                structure.structureHash(), "research-no-visual-crops", 0, 0, List.of());
        EvidenceManifest evidence = new EvidenceUnitBuilder().build(
                revisionId, source + ":" + version, structure, sources, visuals);
        return new RetrievalChunkBuilder(RESEARCH_COUNTER).build(evidence);
    }

    private CanonicalPageAssembler canonicalAssembler() {
        return new CanonicalPageAssembler(0.70, "e1-v5".equals(canonicalMode()));
    }

    private String canonicalMode() {
        String mode = System.getenv().getOrDefault("MATERIAL_RAG_CANONICAL_MODE", "e1-v5");
        if (!CANONICAL_MODES.contains(mode)) {
            throw new IllegalArgumentException("Unknown MATERIAL_RAG_CANONICAL_MODE: " + mode);
        }
        return mode;
    }

    private List<IndexedChunk> indexedChunks(String runId, ProjectionSet projections) {
        List<IndexedChunk> result = new ArrayList<>();
        projections.bySourceVersion().forEach((sourceVersion, manifest) -> manifest.chunks().stream()
                .filter(chunk -> chunk.indexMode() == RetrievalIndexMode.DENSE_AND_LEXICAL)
                .forEach(chunk -> result.add(new IndexedChunk(runId + "_" + result.size(),
                        sourceVersion, chunk))));
        return List.copyOf(result);
    }

    private List<float[]> embedPassages(PineconeVectorClient client, List<String> passages) {
        List<float[]> result = new ArrayList<>();
        for (int start = 0; start < passages.size(); start += 96) {
            result.addAll(client.embed(passages.subList(start, Math.min(start + 96, passages.size())), "passage"));
        }
        return List.copyOf(result);
    }

    private DenseMetrics evaluate(PineconeVectorClient client, String namespace, String tenantKey,
                                  List<ResearchCase> cases, Map<String, ResearchAnchor> anchors,
                                  ProjectionSet projections, List<IndexedChunk> indexed) {
        List<CaseRank> ranks = new ArrayList<>();
        Map<String, IndexedChunk> indexedByVectorId = indexed.stream().collect(
                java.util.stream.Collectors.toMap(IndexedChunk::vectorId, value -> value));
        for (ResearchCase researchCase : cases) {
            List<EvidenceRequirement> requirements = researchCase.requiredEvidenceGroups().stream()
                    .flatMap(group -> group.evidence().stream()).toList();
            List<ResearchAnchor> required = requirements.stream()
                    .map(requirement -> anchors.get(requirement.anchorId())).toList();
            Set<String> sourceVersions = required.stream().map(ResearchAnchor::sourceVersion)
                    .collect(java.util.stream.Collectors.toSet());
            if (sourceVersions.size() != 1) {
                throw new IllegalStateException("A controlled case must use one source version: "
                        + researchCase.caseId());
            }
            String sourceVersion = sourceVersions.iterator().next();
            Map<String, Set<String>> requiredGoldVectorIds = new LinkedHashMap<>();
            for (EvidenceRequirement requirement : requirements) {
                ResearchAnchor anchor = anchors.get(requirement.anchorId());
                Set<String> goldChunkIds = goldChunkIds(
                        projections.bySourceVersion().get(sourceVersion), anchor);
                requiredGoldVectorIds.put(requirement.anchorId(), indexed.stream()
                        .filter(value -> value.sourceVersion().equals(sourceVersion)
                                && goldChunkIds.contains(value.chunk().chunkId()))
                        .map(IndexedChunk::vectorId).collect(java.util.stream.Collectors.toSet()));
            }
            float[] query = client.embedOne(researchCase.query(), "query");
            List<String> matches = client.query(namespace, query, 40, Map.of("$and", List.of(
                    Map.of("tenant_key", Map.of("$eq", tenantKey)),
                    Map.of("version_id", Map.of("$eq", sourceVersion)))));
            List<CandidateResult> candidates = new ArrayList<>();
            for (int index = 0; index < matches.size(); index++) {
                IndexedChunk candidate = indexedByVectorId.get(matches.get(index));
                candidates.add(new CandidateResult(index + 1, matches.get(index),
                        candidate == null ? "unknown" : candidate.sourceVersion(),
                        candidate == null ? "unknown" : candidate.chunk().chunkId()));
            }
            ranks.add(new CaseRank(researchCase, completeEvidenceRank(
                    matches, researchCase.requiredEvidenceGroups(), requiredGoldVectorIds),
                    isMappable(researchCase.requiredEvidenceGroups(), requiredGoldVectorIds),
                    List.copyOf(candidates)));
        }
        SliceMetric total = summarize("all", ranks);
        List<CaseRank> mappedRanks = ranks.stream().filter(CaseRank::mappable).toList();
        SliceMetric conditional = summarize("conditional-on-mapping", mappedRanks);
        List<SliceMetric> slices = new ArrayList<>();
        ranks.stream().map(value -> "language:" + value.researchCase().language()).distinct().sorted()
                .forEach(label -> slices.add(summarize(label, ranks.stream().filter(value ->
                        label.equals("language:" + value.researchCase().language())).toList())));
        ranks.stream().map(value -> "category:" + value.researchCase().category()).distinct().sorted()
                .forEach(label -> slices.add(summarize(label, ranks.stream().filter(value ->
                        label.equals("category:" + value.researchCase().category())).toList())));
        ranks.stream().map(value -> "primaryCategory:" + value.researchCase().primaryCategory())
                .distinct().sorted().forEach(label -> slices.add(summarize(label,
                        ranks.stream().filter(value -> label.equals("primaryCategory:"
                                + value.researchCase().primaryCategory())).toList())));
        List<String> misses = ranks.stream().filter(value -> value.rank() == 0)
                .map(value -> value.researchCase().caseId()).toList();
        List<String> weak = ranks.stream().filter(value -> value.rank() == 0 || value.rank() > 5)
                .map(value -> value.researchCase().caseId() + ":rank=" + value.rank()
                        + ":anchors=" + value.researchCase().goldAnchorIds()).toList();
        List<CaseResult> caseResults = ranks.stream().map(value -> new CaseResult(
                value.researchCase().caseId(), value.rank(), value.researchCase().category(),
                value.researchCase().primaryCategory(), value.researchCase().language(),
                value.researchCase().goldAnchorIds(), value.mappable(), value.candidates())).toList();
        return new DenseMetrics((double) mappedRanks.size() / ranks.size(), mappedRanks.size(),
                total.recallAt1(), total.recallAt5(), total.recallAt10(), total.recallAt40(),
                total.mrrAt10(), conditional, misses, List.copyOf(slices), weak, caseResults);
    }

    private SliceMetric summarize(String label, List<CaseRank> ranks) {
        double count = ranks.size();
        long at1 = ranks.stream().filter(value -> value.rank() == 1).count();
        long at5 = ranks.stream().filter(value -> value.rank() >= 1 && value.rank() <= 5).count();
        long at10 = ranks.stream().filter(value -> value.rank() >= 1 && value.rank() <= 10).count();
        long at40 = ranks.stream().filter(value -> value.rank() >= 1 && value.rank() <= 40).count();
        double reciprocalRank = ranks.stream().filter(value -> value.rank() >= 1 && value.rank() <= 10)
                .mapToDouble(value -> 1.0 / value.rank()).sum();
        return new SliceMetric(label, ranks.size(), at1 / count, at5 / count,
                at10 / count, at40 / count, reciprocalRank / count);
    }

    private int completeEvidenceRank(List<String> matches, List<RequiredEvidenceGroup> groups,
                                     Map<String, Set<String>> vectorsByAnchor) {
        int completeRank = 0;
        for (RequiredEvidenceGroup group : groups) {
            List<Integer> evidenceRanks = group.evidence().stream().map(requirement ->
                    requirement.grade() < requirement.minimumGrade() ? 0
                            : firstEvidenceRank(matches, vectorsByAnchor.getOrDefault(
                            requirement.anchorId(), Set.of()))).toList();
            int groupRank;
            if (group.operator() == EvidenceGroupOperator.ANY) {
                groupRank = evidenceRanks.stream().filter(rank -> rank > 0)
                        .mapToInt(Integer::intValue).min().orElse(0);
            } else {
                if (evidenceRanks.stream().anyMatch(rank -> rank == 0)) return 0;
                groupRank = evidenceRanks.stream().mapToInt(Integer::intValue).max().orElse(0);
            }
            if (groupRank == 0) return 0;
            completeRank = Math.max(completeRank, groupRank);
        }
        return completeRank;
    }

    private boolean isMappable(List<RequiredEvidenceGroup> groups,
                               Map<String, Set<String>> vectorsByAnchor) {
        for (RequiredEvidenceGroup group : groups) {
            List<Boolean> mapped = group.evidence().stream().map(requirement ->
                    requirement.grade() >= requirement.minimumGrade()
                            && !vectorsByAnchor.getOrDefault(requirement.anchorId(), Set.of()).isEmpty())
                    .toList();
            if (group.operator() == EvidenceGroupOperator.ANY
                    && mapped.stream().noneMatch(Boolean::booleanValue)) {
                return false;
            }
            if (group.operator() == EvidenceGroupOperator.ALL_PARTS
                    && mapped.stream().anyMatch(value -> !value)) {
                return false;
            }
        }
        return true;
    }

    private int firstEvidenceRank(List<String> matches, Set<String> gold) {
        for (int index = 0; index < matches.size(); index++) {
            if (gold.contains(matches.get(index))) return index + 1;
        }
        return 0;
    }

    private Set<String> goldChunkIds(RetrievalProjectionManifest manifest, ResearchAnchor anchor) {
        Set<String> exact = exactGoldChunkIds(manifest, anchor.goldMatch());
        if (!exact.isEmpty() || manifest == null || anchor.pageNo() <= 0
                || !anchor.allowPageFallback()) return exact;
        return manifest.chunks().stream()
                .filter(chunk -> pageNo(chunk.pageId()) == anchor.pageNo())
                .map(RetrievalChunkProjection::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Set<String> exactGoldChunkIds(RetrievalProjectionManifest manifest, String goldMatch) {
        if (manifest == null) return Set.of();
        String needle = normalize(goldMatch);
        return manifest.chunks().stream()
                .filter(chunk -> normalize(chunk.retrievalText()).contains(needle))
                .map(RetrievalChunkProjection::chunkId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private String normalize(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private int percentile(List<Integer> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private static RetrievalTokenCounter researchTokenCounter() {
        String tokenizerPath = System.getenv("MATERIAL_RAG_TOKENIZER_PATH");
        if (tokenizerPath != null && !tokenizerPath.isBlank()) {
            String tokenizerSha = System.getenv().getOrDefault("MATERIAL_RAG_TOKENIZER_SHA256",
                    "62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626");
            return new MultilingualE5TokenCounter(Path.of(tokenizerPath), tokenizerSha);
        }
        return new RetrievalTokenCounter() {
            @Override public int count(String text) {
                // Deterministic fallback for fixture-only CI; live research supplies the production tokenizer.
                return Math.max(1, text.codePointCount(0, text.length()));
            }
            @Override public String fingerprint() { return "research-codepoint-counter-v1"; }
        };
    }

    private List<ResearchAnchor> anchors(Path root) throws Exception {
        JsonNode values = JSON.readTree(root.resolve("fixtures/generated/ground-truth.json").toFile())
                .path("anchors");
        List<ResearchAnchor> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(new ResearchAnchor(value.path("anchorId").asText(), value.path("source").asText(),
                    value.path("version").asText(), value.path("modality").asText(),
                    value.path("goldMatch").asText(), value.path("page").asInt(), false));
        }
        return List.copyOf(result);
    }

    private List<ResearchCase> cases(Path root) throws Exception {
        List<ResearchCase> result = new ArrayList<>();
        try (Stream<String> lines = Files.lines(root.resolve("fixtures/generated/cases.jsonl"))) {
            for (String line : lines.filter(value -> !value.isBlank()).toList()) {
                JsonNode value = JSON.readTree(line);
                JsonNode gold = value.path("goldAnchorIds");
                List<String> goldAnchorIds = gold.isEmpty() ? List.of() : JSON.convertValue(gold,
                        JSON.getTypeFactory().constructCollectionType(List.class, String.class));
                List<RequiredEvidenceGroup> groups = requiredEvidenceGroups(value, goldAnchorIds);
                result.add(new ResearchCase(value.path("caseId").asText(), value.path("category").asText(),
                        value.path("primaryCategory").asText(), value.path("language").asText(),
                        value.path("query").asText(),
                        value.path("split").asText("development"), value.path("answerable").asBoolean(),
                        goldAnchorIds, groups));
            }
        }
        return List.copyOf(result);
    }

    private List<RequiredEvidenceGroup> requiredEvidenceGroups(JsonNode value, List<String> fallbackAnchors) {
        JsonNode groups = value.path("requiredEvidenceGroups");
        if (groups.isMissingNode() || groups.isEmpty()) {
            if (fallbackAnchors.isEmpty()) return List.of();
            return List.of(new RequiredEvidenceGroup("legacy", EvidenceGroupOperator.ALL_PARTS,
                    fallbackAnchors.stream().map(anchor -> new EvidenceRequirement(anchor, 3, 3)).toList()));
        }
        List<RequiredEvidenceGroup> result = new ArrayList<>();
        for (JsonNode group : groups) {
            List<EvidenceRequirement> evidence = new ArrayList<>();
            for (JsonNode requirement : group.path("evidence")) {
                int minimumGrade = requirement.path("minimumGrade").asInt();
                evidence.add(new EvidenceRequirement(requirement.path("anchorId").asText(),
                        requirement.path("grade").asInt(minimumGrade), minimumGrade));
            }
            result.add(new RequiredEvidenceGroup(group.path("groupId").asText(),
                    EvidenceGroupOperator.valueOf(group.path("operator").asText()), List.copyOf(evidence)));
        }
        return List.copyOf(result);
    }

    private List<OpenCase> openCases(Path root) throws Exception {
        List<OpenCase> result = new ArrayList<>();
        try (Stream<String> lines = Files.lines(root.resolve("sources/open-cases.jsonl"))) {
            for (String line : lines.filter(value -> !value.isBlank()).toList()) {
                JsonNode value = JSON.readTree(line);
                result.add(new OpenCase(value.path("caseId").asText(), value.path("sourceId").asText(),
                        value.path("page").asInt(), value.path("language").asText(), value.path("query").asText(),
                        value.path("goldMatch").asText()));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> metadata(String tenantKey, IndexedChunk value) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant_key", tenantKey);
        metadata.put("material_id", "material-" + value.sourceVersion());
        metadata.put("version_id", value.sourceVersion());
        metadata.put("revision_id", "revision-" + value.sourceVersion());
        metadata.put("retrieval_chunk_id", value.chunk().chunkId());
        metadata.put("chunk_type", value.chunk().chunkType().name());
        metadata.put("modality", value.chunk().modality().name());
        metadata.put("page_no", pageNo(value.chunk().pageId()));
        metadata.put("language", value.chunk().languagePrimary());
        metadata.put("index_generation_id", "ig-research-v1");
        return metadata;
    }

    private int pageNo(String pageId) {
        if (pageId == null) return 0;
        int marker = pageId.lastIndexOf("-page-");
        return marker < 0 ? 0 : Integer.parseInt(pageId.substring(marker + 6));
    }

    private void waitUntilSearchable(PineconeVectorClient client, String namespace, String tenantKey,
                                     List<IndexedChunk> indexed, List<float[]> vectors) throws Exception {
        List<String> vectorIds = indexed.stream().map(IndexedChunk::vectorId).toList();
        int maxAttempts = Integer.parseInt(
                System.getenv().getOrDefault("MATERIAL_RAG_INDEX_WAIT_ATTEMPTS", "24"));
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (!allExisting(client, namespace, vectorIds)) {
                Thread.sleep(500L);
                continue;
            }
            boolean searchable = true;
            int sampleCount = Math.min(12, indexed.size());
            for (int sample = 0; sample < sampleCount; sample++) {
                int index = sampleCount == 1 ? 0
                        : sample * (indexed.size() - 1) / (sampleCount - 1);
                if (!client.query(namespace, vectors.get(index), 1,
                        Map.of("tenant_key", Map.of("$eq", tenantKey))).contains(indexed.get(index).vectorId())) {
                    searchable = false;
                    break;
                }
            }
            if (searchable) return;
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Controlled PDF vectors were not searchable within "
                + (maxAttempts / 2) + " seconds");
    }

    private void waitUntilDeleted(PineconeVectorClient client, String namespace,
                                  List<String> vectorIds) throws Exception {
        if (vectorIds.isEmpty()) return;
        int maxAttempts = Integer.parseInt(
                System.getenv().getOrDefault("MATERIAL_RAG_DELETE_WAIT_ATTEMPTS", "20"));
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (noneExisting(client, namespace, vectorIds)) return;
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Controlled PDF research vectors remained after cleanup");
    }

    private boolean allExisting(PineconeVectorClient client, String namespace, List<String> vectorIds) {
        for (int start = 0; start < vectorIds.size(); start += 100) {
            List<String> batch = vectorIds.subList(start, Math.min(start + 100, vectorIds.size()));
            if (!client.fetchExisting(namespace, batch).containsAll(batch)) return false;
        }
        return true;
    }

    private boolean noneExisting(PineconeVectorClient client, String namespace, List<String> vectorIds) {
        for (int start = 0; start < vectorIds.size(); start += 100) {
            List<String> batch = vectorIds.subList(start, Math.min(start + 100, vectorIds.size()));
            if (!client.fetchExisting(namespace, batch).isEmpty()) return false;
        }
        return true;
    }

    private Path researchRoot() {
        Path direct = Path.of("evaluation/material-rag-research-v1");
        if (Files.exists(direct)) return direct;
        Path parent = Path.of("../evaluation/material-rag-research-v1");
        if (Files.exists(parent)) return parent;
        throw new IllegalStateException("material RAG research fixtures are unavailable");
    }

    private record ProjectionSet(Map<String, RetrievalProjectionManifest> bySourceVersion) { }

    private record ResearchAnchor(String anchorId, String source, String version,
                                  String modality, String goldMatch, int pageNo,
                                  boolean allowPageFallback) {
        String sourceVersion() { return source + ":" + version; }
    }

    private record ResearchCase(String caseId, String category, String primaryCategory,
                                String language, String query, String split, boolean answerable,
                                List<String> goldAnchorIds,
                                List<RequiredEvidenceGroup> requiredEvidenceGroups) { }

    private record EvidenceRequirement(String anchorId, int grade, int minimumGrade) { }

    private record RequiredEvidenceGroup(String groupId, EvidenceGroupOperator operator,
                                         List<EvidenceRequirement> evidence) { }

    private enum EvidenceGroupOperator { ANY, ALL_PARTS }

    private record OpenCase(String caseId, String sourceId, int page, String language,
                            String query, String goldMatch) { }

    private record IndexedChunk(String vectorId, String sourceVersion, RetrievalChunkProjection chunk) { }

    private record CaseRank(ResearchCase researchCase, int rank, boolean mappable,
                            List<CandidateResult> candidates) { }

    private record CandidateResult(int rank, String vectorId, String sourceVersion, String chunkId) { }

    private record CaseResult(String caseId, int rank, String category, String primaryCategory,
                              String language, List<String> goldAnchorIds, boolean mappable,
                              List<CandidateResult> candidates) { }

    private record SliceMetric(String label, int count, double recallAt1, double recallAt5,
                               double recallAt10, double recallAt40, double mrrAt10) { }

    private record DenseMetrics(double mappingRate, int mappableCaseCount, double recallAt1,
                                double recallAt5, double recallAt10, double recallAt40, double mrrAt10,
                                SliceMetric conditional, List<String> misses,
                                List<SliceMetric> slices, List<String> weakCases,
                                List<CaseResult> caseResults) { }

    private record ExperimentResult(String runId, String canonicalFingerprint,
                                    DenseMetrics metrics, int chunkCount) { }
}
