package org.zipp.ai.ingestion.worker.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zipp.ai.domain.ingestion.model.valobj.CanonicalPage;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceModality;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceManifest;
import org.zipp.ai.domain.ingestion.model.valobj.EvidenceSourcePage;
import org.zipp.ai.domain.ingestion.model.valobj.NormalizedBoundingBox;
import org.zipp.ai.domain.ingestion.model.valobj.OcrResult;
import org.zipp.ai.domain.ingestion.model.valobj.OcrWord;
import org.zipp.ai.domain.ingestion.model.valobj.ParsedDocument;
import org.zipp.ai.domain.ingestion.model.valobj.ParsedPage;
import org.zipp.ai.domain.ingestion.model.valobj.StoredArtifact;
import org.zipp.ai.domain.ingestion.model.valobj.VisualCropManifest;
import org.zipp.ai.domain.ingestion.model.valobj.VisualCropArtifact;
import org.zipp.ai.domain.ingestion.service.VisualCandidateSelectionPolicy;
import org.zipp.ai.domain.ingestion.service.CanonicalPageAssembler;
import org.zipp.ai.domain.ingestion.service.DocumentStructureBuilder;
import org.zipp.ai.domain.ingestion.service.EvidenceUnitBuilder;
import org.zipp.ai.domain.ingestion.service.OcrSelectionPolicy;
import org.zipp.ai.domain.ingestion.port.OcrEnginePort;
import org.zipp.ai.domain.retrieval.model.valobj.ChunkEvidenceRole;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalChunkType;
import org.zipp.ai.domain.retrieval.model.valobj.RetrievalIndexMode;
import org.zipp.ai.domain.retrieval.port.RetryableRetrievalException;
import org.zipp.ai.domain.retrieval.projection.RetrievalChunkBuilder;
import org.zipp.ai.domain.retrieval.projection.RetrievalChunkProjection;
import org.zipp.ai.domain.retrieval.projection.RetrievalEvidenceMapping;
import org.zipp.ai.domain.retrieval.projection.RetrievalProjectionManifest;
import org.zipp.ai.domain.retrieval.projection.RetrievalTokenCounter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorClient;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorRecord;
import org.zipp.ai.ingestion.worker.document.PdfBoxDocumentParser;
import org.zipp.ai.ingestion.worker.document.MultilingualE5TokenCounter;
import org.zipp.ai.ingestion.worker.document.TesseractOcrEngine;
import org.zipp.ai.ingestion.worker.document.VisualCropDeriver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Project-pipeline experiment: PDFBox -> canonical Evidence -> project chunks -> Pinecone recall. */
class ControlledPdfDenseRecallLiveTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final RetrievalTokenCounter RESEARCH_COUNTER = researchTokenCounter();
    private static final Set<String> CANONICAL_MODES = Set.of("e0-v4", "e1-v5");
    private static final long MAX_RESEARCH_PAGE_ARTIFACT_BYTES = 20L * 1024 * 1024;

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
    void chunkModeShouldSelectLeafOrExistingParentText() {
        RetrievalChunkProjection chunk = chunkWithParentContext("parent text");

        assertEquals("leaf text", ChunkMode.FLAT_LEAF.embeddingText(chunk));
        assertEquals("parent text", ChunkMode.PARENT_CONTEXT_500.embeddingText(chunk));
    }

    @Test
    void parentModeShouldKeepGoldBoundToTheFixedChildProjection() {
        RetrievalChunkProjection chunk = chunkWithParentContext("neighbor evidence\n\nleaf text");
        IndexedChunk indexed = new IndexedChunk("parent-vector", "source:v1", chunk,
                ChunkMode.PARENT_CONTEXT_500.embeddingText(chunk));

        assertTrue(fixedGoldVectorIds(
                List.of(indexed), "source:v1", Set.of("neighbor-chunk")).isEmpty());
        assertEquals(Set.of("parent-vector"), fixedGoldVectorIds(
                List.of(indexed), "source:v1", Set.of("chunk-1")));
    }

    @Test
    void parentModeShouldFallBackToLeafAboveTheSafeEmbeddingLimit() {
        RetrievalChunkProjection chunk = chunkWithParentContext("x".repeat(501));

        assertEquals("leaf text", ChunkMode.PARENT_CONTEXT_500.embeddingText(chunk));
    }

    @Test
    void transientResearchCallShouldHonorABoundedRetry() throws Exception {
        int[] attempts = {0};

        String result = retryPinecone("test", 2, () -> {
            if (attempts[0]++ == 0) {
                throw new RetryableRetrievalException("rate limited", Duration.ZERO);
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(2, attempts[0]);
    }

    @Test
    void e4ChartbookCasesShouldKeepEveryGoldSourceInsideTheMountedScope() throws Exception {
        Map<String, ResearchAnchor> anchors = anchors(researchRoot()).stream()
                .collect(java.util.stream.Collectors.toMap(ResearchAnchor::anchorId, value -> value));
        List<ResearchCase> cases = chartbookCases(researchRoot());

        assertEquals(26, cases.stream().filter(value -> "development".equals(value.split())).count());
        assertEquals(20, cases.stream().filter(value -> "validation".equals(value.split())).count());
        for (ResearchCase researchCase : cases) {
            Set<String> resolvedGoldSources = researchCase.goldAnchorIds().stream()
                    .map(anchors::get).map(ResearchAnchor::sourceVersion)
                    .collect(java.util.stream.Collectors.toSet());
            assertEquals(Set.copyOf(researchCase.goldSourceVersions()), resolvedGoldSources);
            assertTrue(Set.copyOf(researchCase.mountedSourceVersions()).containsAll(resolvedGoldSources));
        }
    }

    @Test
    void drawioGenerationCasesShouldUseTheFrozenDevelopmentChartbook() throws Exception {
        List<ResearchCase> tasks = drawioGenerationCases(researchRoot());
        Set<String> noRetrievalTasks = drawioGenerationNoRetrievalTaskIds(researchRoot());

        assertEquals(5, tasks.size());
        assertTrue(tasks.stream().allMatch(value -> "development".equals(value.split())));
        assertEquals(Set.of("dgt-dev-06"), noRetrievalTasks);
        assertTrue(tasks.stream().allMatch(value -> value.mountedSourceVersions().equals(List.of(
                "drawio-agent-architecture:v1", "drawio-planning-workshop-scan:v1",
                "drawio-workflow-handbook:v1"))));
    }

    @Test
    void architectureVisualHydrationProjectionShouldIndexTheRasterRouteOnPageThree() throws Exception {
        Set<Integer> ocrPageNumbers = new HashSet<>();
        RetrievalProjectionManifest projection = buildVisualProjection(researchRoot(),
                "drawio-agent-architecture", "v1", "drawio-agent-architecture-blueprint-v1.pdf",
                (pageImage, pageNo) -> {
                    ocrPageNumbers.add(pageNo);
                    return new OcrResult(pageNo,
                        "SCOPE SOURCES RETRIEVE EVIDENCE BUILD PLAN COMPOSE CANVAS", 0.99,
                        List.of(
                                new OcrWord("SCOPE", new NormalizedBoundingBox(0.10, 0.28, 0.20, 0.34),
                                        0.99, "line:1"),
                                new OcrWord("SOURCES", new NormalizedBoundingBox(0.22, 0.28, 0.34, 0.34),
                                        0.99, "line:2"),
                                new OcrWord("RETRIEVE", new NormalizedBoundingBox(0.36, 0.28, 0.48, 0.34),
                                        0.99, "line:3"),
                                new OcrWord("EVIDENCE", new NormalizedBoundingBox(0.50, 0.28, 0.62, 0.34),
                                        0.99, "line:4"),
                                new OcrWord("BUILD", new NormalizedBoundingBox(0.22, 0.58, 0.32, 0.64),
                                        0.99, "line:5"),
                                new OcrWord("PLAN", new NormalizedBoundingBox(0.38, 0.58, 0.46, 0.64),
                                        0.99, "line:6"),
                                new OcrWord("COMPOSE", new NormalizedBoundingBox(0.52, 0.58, 0.64, 0.64),
                                        0.99, "line:7"),
                                new OcrWord("CANVAS", new NormalizedBoundingBox(0.68, 0.58, 0.78, 0.64),
                                        0.99, "line:8")));
                });

        assertEquals(Set.of(3), ocrPageNumbers);
        assertTrue(projection.chunks().stream().anyMatch(chunk -> chunk.modality() == EvidenceModality.VISUAL
                && pageNo(chunk.pageId()) == 3 && chunk.indexMode() == RetrievalIndexMode.DENSE_AND_LEXICAL
                && chunk.retrievalText().contains(
                "Figure 2. Evidence-to-canvas request route for an editable draw.io flow.")));
        assertTrue(projection.chunks().stream().anyMatch(chunk -> chunk.modality() == EvidenceModality.TEXT
                && pageNo(chunk.pageId()) == 3 && chunk.indexMode() == RetrievalIndexMode.DENSE_AND_LEXICAL
                && chunk.retrievalText().contains(
                "EVIDENCE")));
        assertTrue(projection.chunks().stream().anyMatch(chunk -> chunk.chunkType() == RetrievalChunkType.PAGE_PARENT
                && pageNo(chunk.pageId()) == 3 && chunk.citable()
                && chunk.indexMode() == RetrievalIndexMode.LEXICAL_ONLY));
    }

    private RetrievalChunkProjection chunkWithParentContext(String parentContext) {
        return new RetrievalChunkProjection(
                "chunk-1", "page-1", "section-1", RetrievalChunkType.CONTENT,
                EvidenceModality.TEXT, "en", true, RetrievalIndexMode.DENSE_AND_LEXICAL,
                "leaf text", "a".repeat(64), parentContext, List.of("evidence-1"),
                2, 1.0, 1, List.of(new RetrievalEvidenceMapping(
                "evidence-1", ChunkEvidenceRole.PRIMARY, 0, null, null)));
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
        ChunkMode chunkMode = chunkMode();
        RetrievalMode retrievalMode = retrievalMode();
        ProjectionSet projections = buildControlledProjections(root);
        Map<String, ResearchAnchor> anchors = new HashMap<>();
        anchors(root).forEach(anchor -> anchors.put(anchor.anchorId(), anchor));
        String researchSplit = System.getenv().getOrDefault("MATERIAL_RAG_RESEARCH_SPLIT", "development");
        assertTrue(Set.of("development", "validation", "holdout").contains(researchSplit),
                "Unknown research split: " + researchSplit);
        String caseProfile = System.getenv().getOrDefault("MATERIAL_RAG_CASE_PROFILE", "core-v1");
        List<ResearchCase> authoredCases = switch (caseProfile) {
            case "core-v1" -> cases(root);
            case "drawio-core-v1" -> drawioCoreCases(root, researchSplit);
            case "e4-chartbook-v1" -> chartbookCases(root);
            default -> throw new IllegalArgumentException("Unknown case profile: " + caseProfile);
        };
        List<ResearchCase> cases = authoredCases.stream()
                .filter(ResearchCase::answerable)
                .filter(value -> researchSplit.equals(value.split()))
                .filter(value -> !value.goldAnchorIds().isEmpty()
                        && value.goldAnchorIds().stream().map(anchors::get).allMatch(anchor ->
                        anchor != null && Set.of("text", "table").contains(anchor.modality())))
                .toList();
        assertFalse(cases.isEmpty());

        PineconeVectorClient client = new PineconeVectorClient(
                apiKey, indexHost, "multilingual-e5-large", 1024, JSON);
        String pairedDenseRerank = System.getenv("MATERIAL_RAG_PAIRED_DENSE_RERANK_RESULT_JSON");
        String pairedLlmRerank = System.getenv("MATERIAL_RAG_PAIRED_LLM_RERANK_RESULT_JSON");
        boolean runLlmReranker = pairedDenseRerank != null && !pairedDenseRerank.isBlank()
                && pairedLlmRerank != null && !pairedLlmRerank.isBlank();
        ResearchLlmReranker reranker = runLlmReranker ? rerankerFromEnvironment() : null;
        String rerankerModel = runLlmReranker
                ? requiredEnvironment("MATERIAL_RAG_RERANKER_MODEL") : "none";
        ExperimentResult result = runRetrievalExperiment(
                "controlled", client, namespace, projections, cases, anchors, chunkMode,
                reranker, rerankerModel);
        String pairedRawPostprocess = System.getenv("MATERIAL_RAG_PAIRED_RAW_POSTPROCESS_RESULT_JSON");
        String pairedDedupPostprocess = System.getenv("MATERIAL_RAG_PAIRED_DEDUP_RESULT_JSON");
        String pairedChartbookRaw = System.getenv("MATERIAL_RAG_PAIRED_CHARTBOOK_RAW_RESULT_JSON");
        String pairedChartbookDiversified = System.getenv(
                "MATERIAL_RAG_PAIRED_CHARTBOOK_DIVERSIFIED_RESULT_JSON");
        String pairedOriginalQuery = System.getenv("MATERIAL_RAG_PAIRED_ORIGINAL_QUERY_RESULT_JSON");
        String pairedRewrittenQuery = System.getenv("MATERIAL_RAG_PAIRED_REWRITTEN_QUERY_RESULT_JSON");
        String pairedDense = System.getenv("MATERIAL_RAG_PAIRED_DENSE_RESULT_JSON");
        String pairedHybrid = System.getenv("MATERIAL_RAG_PAIRED_HYBRID_RESULT_JSON");
        if (runLlmReranker) {
            report("Controlled PDF", RerankerMode.NONE.id(), result.metrics(RerankerMode.NONE), result);
            report("Controlled PDF", RerankerMode.LLM_LISTWISE.id(),
                    result.metrics(RerankerMode.LLM_LISTWISE), result);
            writeRawResult(root, researchSplit, canonicalMode, chunkMode, RetrievalMode.DENSE,
                    QueryMode.ORIGINAL, PostprocessMode.RANKED_RAW, RerankerMode.NONE,
                    result.metrics(RerankerMode.NONE), result, pairedDenseRerank);
            writeRawResult(root, researchSplit, canonicalMode, chunkMode, RetrievalMode.DENSE,
                    QueryMode.ORIGINAL, PostprocessMode.RANKED_RAW, RerankerMode.LLM_LISTWISE,
                    result.metrics(RerankerMode.LLM_LISTWISE), result, pairedLlmRerank);
        } else if (pairedChartbookRaw != null && !pairedChartbookRaw.isBlank()
                && pairedChartbookDiversified != null && !pairedChartbookDiversified.isBlank()) {
            report("Controlled PDF", PostprocessMode.RANKED_RAW.id(),
                    result.metrics(PostprocessMode.RANKED_RAW), result);
            report("Controlled PDF", PostprocessMode.SOURCE_DIVERSITY.id(),
                    result.metrics(PostprocessMode.SOURCE_DIVERSITY), result);
            writeRawResult(root, researchSplit, canonicalMode, chunkMode, RetrievalMode.DENSE,
                    QueryMode.ORIGINAL, PostprocessMode.RANKED_RAW, RerankerMode.NONE,
                    result.metrics(PostprocessMode.RANKED_RAW), result, pairedChartbookRaw);
            writeRawResult(root, researchSplit, canonicalMode, chunkMode, RetrievalMode.DENSE,
                    QueryMode.ORIGINAL, PostprocessMode.SOURCE_DIVERSITY, RerankerMode.NONE,
                    result.metrics(PostprocessMode.SOURCE_DIVERSITY), result,
                    pairedChartbookDiversified);
        } else if (pairedRawPostprocess != null && !pairedRawPostprocess.isBlank()
                && pairedDedupPostprocess != null && !pairedDedupPostprocess.isBlank()) {
            report("Controlled PDF", PostprocessMode.RANKED_RAW.id(),
                    result.metrics(PostprocessMode.RANKED_RAW), result);
            report("Controlled PDF", PostprocessMode.EVIDENCE_DEDUP.id(),
                    result.metrics(PostprocessMode.EVIDENCE_DEDUP), result);
            writeRawResult(root, researchSplit, canonicalMode, chunkMode, RetrievalMode.DENSE,
                    QueryMode.ORIGINAL, PostprocessMode.RANKED_RAW, RerankerMode.NONE,
                    result.metrics(PostprocessMode.RANKED_RAW), result, pairedRawPostprocess);
            writeRawResult(root, researchSplit, canonicalMode, chunkMode, RetrievalMode.DENSE,
                    QueryMode.ORIGINAL, PostprocessMode.EVIDENCE_DEDUP, RerankerMode.NONE,
                    result.metrics(PostprocessMode.EVIDENCE_DEDUP), result, pairedDedupPostprocess);
        } else if (pairedOriginalQuery != null && !pairedOriginalQuery.isBlank()
                && pairedRewrittenQuery != null && !pairedRewrittenQuery.isBlank()) {
            report("Controlled PDF", QueryMode.ORIGINAL.id(), result.metrics(QueryMode.ORIGINAL), result);
            report("Controlled PDF", QueryMode.EVIDENCE_FOCUSED.id(),
                    result.metrics(QueryMode.EVIDENCE_FOCUSED), result);
            writeRawResult(root, researchSplit, canonicalMode, chunkMode, RetrievalMode.DENSE,
                    QueryMode.ORIGINAL, PostprocessMode.RANKED_RAW, RerankerMode.NONE,
                    result.metrics(QueryMode.ORIGINAL), result, pairedOriginalQuery);
            writeRawResult(root, researchSplit, canonicalMode, chunkMode, RetrievalMode.DENSE,
                    QueryMode.EVIDENCE_FOCUSED, PostprocessMode.RANKED_RAW, RerankerMode.NONE,
                    result.metrics(QueryMode.EVIDENCE_FOCUSED), result, pairedRewrittenQuery);
        } else if (pairedDense != null && !pairedDense.isBlank()
                && pairedHybrid != null && !pairedHybrid.isBlank()) {
            report("Controlled PDF", RetrievalMode.DENSE.id(), result.metrics(RetrievalMode.DENSE), result);
            report("Controlled PDF", RetrievalMode.HYBRID_PROJECTION_RRF.id(),
                    result.metrics(RetrievalMode.HYBRID_PROJECTION_RRF), result);
            writeRawResult(root, researchSplit, canonicalMode, chunkMode, RetrievalMode.DENSE,
                    QueryMode.ORIGINAL, PostprocessMode.RANKED_RAW, RerankerMode.NONE,
                    result.metrics(RetrievalMode.DENSE), result, pairedDense);
            writeRawResult(root, researchSplit, canonicalMode, chunkMode,
                    RetrievalMode.HYBRID_PROJECTION_RRF, QueryMode.ORIGINAL,
                    PostprocessMode.RANKED_RAW, RerankerMode.NONE,
                    result.metrics(RetrievalMode.HYBRID_PROJECTION_RRF),
                    result, pairedHybrid);
        } else {
            report("Controlled PDF", retrievalMode.id(), result.metrics(retrievalMode), result);
            writeRawResult(root, researchSplit, canonicalMode, chunkMode, retrievalMode,
                    QueryMode.ORIGINAL, PostprocessMode.RANKED_RAW, RerankerMode.NONE,
                    result.metrics(retrievalMode), result,
                    System.getenv("MATERIAL_RAG_RESULT_JSON"));
        }
        if (Boolean.parseBoolean(System.getenv().getOrDefault("MATERIAL_RAG_ENFORCE_GATES", "true"))) {
            assertTrue(result.metrics(retrievalMode).recallAt10() >= 0.90,
                    "Dense PDF Recall@10 fell below the text gate");
            assertTrue(result.metrics(retrievalMode).recallAt40() >= 0.95,
                    "Dense PDF candidate Recall@40 fell below the gate");
        }
    }

    /**
     * Opt-in E6b input producer. It deliberately requires a real OCR executable and an explicit
     * output path, so a text-only baseline can never be mistaken for multimodal hydration.
     */
    @Test
    void shouldExportDrawioDevelopmentTaskHydrationFromTheRealMultimodalPipeline() throws Exception {
        String apiKey = System.getenv("PINECONE_API_KEY");
        String indexHost = System.getenv("PINECONE_INDEX_HOST");
        String namespace = System.getenv().getOrDefault("PINECONE_NAMESPACE", "recall-test");
        String output = System.getenv("MATERIAL_RAG_TASK_HYDRATION_JSON");
        String tesseract = System.getenv("MATERIAL_RAG_TESSERACT_EXECUTABLE");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank()
                && indexHost != null && !indexHost.isBlank()
                && (namespace.toLowerCase(Locale.ROOT).contains("test")
                || namespace.toLowerCase(Locale.ROOT).contains("dev"))
                && output != null && !output.isBlank()
                && tesseract != null && !tesseract.isBlank() && Files.isExecutable(Path.of(tesseract)));

        Path root = researchRoot();
        ProjectionSet projections = buildDrawioTaskHydrationProjections(root,
                new TesseractOcrEngine(tesseract, "eng+chi_sim", Duration.ofSeconds(30)));
        Map<String, ResearchAnchor> anchorById = new LinkedHashMap<>();
        anchors(root).forEach(anchor -> anchorById.put(anchor.anchorId(), anchor));
        SourceEvidenceIdentityManifest sourceIdentities = SourceEvidenceIdentityManifest.load(
                root.resolve("fixtures/generated/source-evidence-identities-v1.json"), JSON);
        PineconeVectorClient client = new PineconeVectorClient(
                apiKey, indexHost, "multilingual-e5-large", 1024, JSON);
        ExperimentResult result = runRetrievalExperiment("drawiohydration", client, namespace, projections,
                drawioGenerationCases(root), anchorById, chunkMode(), null, "none");
        writeTaskHydrationTrace(root, result, sourceIdentities, drawioGenerationNoRetrievalTaskIds(root), Path.of(output));
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
                    List.of(new EvidenceRequirement(value.caseId(), 3, 3)))),
                    List.of(value.sourceId() + ":pinned"), List.of(),
                    List.of(value.sourceId() + ":pinned")));
        }
        PineconeVectorClient client = new PineconeVectorClient(
                apiKey, indexHost, "multilingual-e5-large", 1024, JSON);
        ExperimentResult result = runRetrievalExperiment(
                "open", client, namespace, projections, List.copyOf(cases), Map.copyOf(anchors),
                ChunkMode.FLAT_LEAF, null, "none");
        report("Open PDF", RetrievalMode.DENSE.id(), result.metrics(RetrievalMode.DENSE), result);
        assertTrue(result.metrics(RetrievalMode.DENSE).recallAt10() >= 0.85,
                "Open PDF Recall@10 fell below research baseline");
        assertTrue(result.metrics(RetrievalMode.DENSE).recallAt40() >= 0.95,
                "Open PDF Recall@40 fell below research baseline");
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
            String currentToken = token;
            var page = retryPinecone("list cleanup vectors", () ->
                    client.listVectorIds(namespace, currentToken, 100));
            ids.addAll(page.vectorIds().stream().filter(value -> value.startsWith(cleanupPrefix)).toList());
            token = page.nextToken();
        } while (token != null);
        for (int start = 0; start < ids.size(); start += 100) {
            int batchStart = start;
            retryPinecone("delete cleanup vectors", () -> {
                client.delete(namespace, ids.subList(
                        batchStart, Math.min(batchStart + 100, ids.size())));
                return null;
            });
        }
        waitUntilDeleted(client, namespace, ids);
        System.out.println("Removed interrupted research vectors: " + ids.size());
    }

    private ExperimentResult runRetrievalExperiment(String prefix, PineconeVectorClient client,
                                                    String namespace, ProjectionSet projections,
                                                    List<ResearchCase> cases,
                                                    Map<String, ResearchAnchor> anchors,
                                                    ChunkMode chunkMode,
                                                    ResearchLlmReranker reranker,
                                                    String rerankerModel) throws Exception {
        String runId = prefix + "pdfresearch_" + UUID.randomUUID().toString().replace("-", "");
        String tenantKey = runId + "_tenant";
        List<IndexedChunk> indexed = indexedChunks(runId, projections, chunkMode);
        List<String> vectorIds = indexed.stream().map(IndexedChunk::vectorId).toList();
        List<Integer> embeddingTokens = indexed.stream()
                .map(value -> RESEARCH_COUNTER.count(value.embeddingText())).sorted().toList();
        EmbeddingProfile embeddingProfile = new EmbeddingProfile(
                percentile(embeddingTokens, 0.50), percentile(embeddingTokens, 0.95),
                embeddingTokens.get(embeddingTokens.size() - 1));
        System.out.printf("Research run started: %s cases=%d chunks=%d "
                        + "embeddingTokens[p50=%d,p95=%d,max=%d]%n",
                runId, cases.size(), indexed.size(), embeddingProfile.p50(),
                embeddingProfile.p95(), embeddingProfile.max());
        try {
            List<float[]> passageVectors = embedPassages(client,
                    indexed.stream().map(IndexedChunk::embeddingText).toList());
            List<PineconeVectorRecord> records = new ArrayList<>();
            for (int index = 0; index < indexed.size(); index++) {
                IndexedChunk value = indexed.get(index);
                records.add(new PineconeVectorRecord(value.vectorId(), passageVectors.get(index),
                        metadata(tenantKey, value)));
            }
            // Keep live experiments within Pinecone's request-size limits for long PDFs.
            for (int start = 0; start < records.size(); start += 100) {
                int batchStart = start;
                retryPinecone("upsert research vectors", () -> {
                    client.upsert(namespace, records.subList(
                            batchStart, Math.min(batchStart + 100, records.size())));
                    return null;
                });
            }
            waitUntilSearchable(client, namespace, tenantKey, indexed, passageVectors);
            System.out.println("Research index is searchable: " + runId);
            return new ExperimentResult(runId, canonicalAssembler().fingerprint(), evaluate(
                    client, namespace, tenantKey, cases, anchors, projections, indexed,
                    reranker, rerankerModel),
                    indexed.size(), embeddingProfile);
        } finally {
            // Pinecone limits delete-by-id payloads, so large open PDFs must be cleaned in batches.
            for (int start = 0; start < vectorIds.size(); start += 100) {
                int batchStart = start;
                retryPinecone("delete research vectors", () -> {
                    client.delete(namespace, vectorIds.subList(
                            batchStart, Math.min(batchStart + 100, vectorIds.size())));
                    return null;
                });
            }
            waitUntilDeleted(client, namespace, vectorIds);
            System.out.println("Research vectors deleted: " + runId);
        }
    }

    private void report(String label, String mode, DenseMetrics metrics, ExperimentResult result) {
        System.out.printf(Locale.ROOT,
                "%s %s pipeline: Mapping=%.4f Recall@1=%.4f Recall@5=%.4f Recall@10=%.4f "
                        + "Recall@40=%.4f MRR@10=%.4f chunks=%d%n",
                label, mode, metrics.mappingRate(), metrics.recallAt1(), metrics.recallAt5(),
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

    private void writeRawResult(Path root, String split, String canonicalMode,
                                ChunkMode chunkMode, RetrievalMode retrievalMode,
                                QueryMode queryMode, PostprocessMode postprocessMode,
                                RerankerMode rerankerMode, DenseMetrics metrics,
                                ExperimentResult result, String configured) throws Exception {
        if (configured == null || configured.isBlank()) return;
        Path output = Path.of(configured).toAbsolutePath().normalize();
        Path lock = root.resolve("fixtures/generated/corpus-lock.json");
        if (!Files.exists(lock)) {
            lock = root.resolve("fixtures/generated/corpus-lock.candidate.json");
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("schemaVersion", "material-rag-retrieval-run-v3");
        raw.put("runId", result.runId());
        raw.put("gitCommit", System.getenv().getOrDefault("MATERIAL_RAG_COMMIT_SHA", "unknown"));
        raw.put("corpusLockSha256", sha256(lock));
        raw.put("split", split);
        raw.put("canonicalMode", canonicalMode);
        raw.put("chunkMode", chunkMode.id());
        raw.put("chunkEmbeddingFingerprint", chunkMode.fingerprint());
        raw.put("retrievalMode", retrievalMode.id());
        raw.put("queryMode", queryMode.id());
        raw.put("queryRewriteFingerprint", ResearchQueryRewriter.FINGERPRINT);
        raw.put("postprocessMode", postprocessMode.id());
        raw.put("rerankerMode", rerankerMode.id());
        raw.put("rerankerFingerprint", ResearchLlmReranker.FINGERPRINT);
        // Record the candidate model in both arms so the paired comparator can freeze it.
        raw.put("rerankerModel", System.getenv().getOrDefault("MATERIAL_RAG_RERANKER_MODEL", "none"));
        raw.put("rerankerEndpointFingerprint", rerankerEndpointFingerprint());
        raw.put("rerankerCandidateLimit", 40);
        raw.put("rerankerUsage", result.rerankerUsage());
        raw.put("dedupFingerprint", ResearchEvidenceDeduplicator.FINGERPRINT);
        raw.put("caseProfile", System.getenv().getOrDefault("MATERIAL_RAG_CASE_PROFILE", "core-v1"));
        raw.put("sourceDiversityFingerprint", ResearchSourceDiversifier.FINGERPRINT);
        raw.put("sourceDiversityHeadLimit", 10);
        raw.put("sourceDiversityPerSourceHeadCap", 4);
        raw.put("lexicalRankerFingerprint", ResearchHybridRanker.FINGERPRINT);
        raw.put("fusionFingerprint", "weighted-rrf-v1:k60:lexical1.2:dense1.0");
        raw.put("canonicalFingerprint", result.canonicalFingerprint());
        raw.put("embeddingModel", "multilingual-e5-large");
        raw.put("tokenizerFingerprint", RESEARCH_COUNTER.fingerprint());
        raw.put("candidateLimit", 40);
        raw.put("retrievalPoolLimit", 80);
        raw.put("chunkCount", result.chunkCount());
        raw.put("embeddingTokenProfile", result.embeddingTokenProfile());
        raw.put("metrics", metrics);
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), raw);
    }

    /** Serialises only retrieved material; task assertions and required anchors are never consulted here. */
    private void writeTaskHydrationTrace(Path root, ExperimentResult result,
                                         SourceEvidenceIdentityManifest sourceIdentities,
                                         Set<String> noRetrievalTasks,
                                         Path output) throws Exception {
        Path lock = root.resolve("fixtures/generated/corpus-lock.json");
        Path identityManifest = root.resolve("fixtures/generated/source-evidence-identities-v1.json");
        String commit = requiredEnvironment("MATERIAL_RAG_COMMIT_SHA");
        if (!commit.matches("[0-9a-f]{7,64}")) {
            throw new IllegalArgumentException("MATERIAL_RAG_COMMIT_SHA must be a Git commit hash");
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (CaseResult caseResult : result.metrics(PostprocessMode.RANKED_RAW).caseResults()) {
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (CandidateResult candidate : caseResult.denseCandidates()) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("rank", candidate.rank());
                value.put("chunkId", candidate.chunkId());
                value.put("sourceVersion", candidate.sourceVersion());
                value.put("page", pageNo(candidate.pageId()));
                value.put("modality", candidate.modality());
                value.put("retrievalTextSha256", sha256(candidate.retrievalText()));
                value.put("evidence", hydratedEvidence(root, candidate, sourceIdentities));
                candidates.add(value);
            }
            tasks.add(Map.of("taskId", caseResult.caseId(), "candidates", candidates));
        }
        noRetrievalTasks.stream().sorted().forEach(taskId -> tasks.add(Map.of("taskId", taskId, "candidates", List.of())));
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("schemaVersion", "material-rag-drawio-task-hydration-candidates-v1");
        trace.put("retrievalRun", Map.of("runId", result.runId(), "gitCommit", commit,
                "corpusLockSha256", sha256(lock)));
        // Persist the source-owned identity input so hydration cannot silently substitute evaluator data.
        trace.put("sourceEvidenceIdentityManifest", Map.of(
                "path", root.relativize(identityManifest).toString().replace('\\', '/'),
                "sha256", sha256(identityManifest)));
        trace.put("tasks", tasks);
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), trace);
    }


    /** Exports every retrieved chunk; only source-registered identity may replace the fallback label. */
    private List<Map<String, Object>> hydratedEvidence(Path root, CandidateResult candidate,
                                                        SourceEvidenceIdentityManifest sourceIdentities) throws Exception {
        int page = pageNo(candidate.pageId());
        List<String> sourceEvidenceIds = sourceIdentities.resolve(
                candidate.sourceVersion(), page, candidate.modality(), candidate.retrievalText());
        List<String> citationIds = sourceEvidenceIds.isEmpty()
                ? List.of("retrieved:" + candidate.chunkId())
                : sourceEvidenceIds;
        List<Map<String, Object>> result = new ArrayList<>();
        for (String citationId : citationIds) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("anchorId", citationId);
            evidence.put("sourceVersion", candidate.sourceVersion());
            evidence.put("page", page);
            evidence.put("text", candidate.retrievalText());
            Path artifact = hydrationArtifact(root, candidate.sourceVersion(), page);
            if (artifact != null) {
                evidence.put("imagePath", root.relativize(artifact).toString().replace('\\', '/'));
                evidence.put("imageSha256", sha256(artifact));
            }
            result.add(evidence);
        }
        return List.copyOf(result);
    }

    /** The image files are frozen source-page artifacts, not model-generated descriptions. */
    private Path hydrationArtifact(Path root, String sourceVersion, int page) {
        String filename = switch (sourceVersion) {
            case "drawio-agent-architecture:v1" -> page == 3 ? "drawio-agent-request-route.png" : null;
            case "drawio-planning-workshop-scan:v1" -> page >= 1 && page <= 6
                    ? "drawio-workshop-scan-page-" + page + ".jpg" : null;
            default -> null;
        };
        if (filename == null) return null;
        Path artifact = root.resolve("fixtures/generated/images").resolve(filename);
        return Files.isRegularFile(artifact) ? artifact : null;
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private String rerankerEndpointFingerprint() throws Exception {
        String baseUrl = System.getenv("MATERIAL_RAG_RERANKER_BASE_URL");
        String completionPath = System.getenv("MATERIAL_RAG_RERANKER_COMPLETIONS_PATH");
        if (baseUrl == null || baseUrl.isBlank() || completionPath == null || completionPath.isBlank()) {
            return "none";
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (baseUrl.replaceAll("/+$", "") + "/" + completionPath.replaceFirst("^/+", ""))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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

    /** Builds the three mounted Development sources, applying OCR to the scan before chunking. */
    private ProjectionSet buildDrawioTaskHydrationProjections(Path root, OcrEnginePort ocr) throws Exception {
        Map<String, RetrievalProjectionManifest> result = new LinkedHashMap<>();
        result.put("drawio-agent-architecture:v1", buildVisualProjection(root,
                "drawio-agent-architecture", "v1", "drawio-agent-architecture-blueprint-v1.pdf", ocr));
        result.put("drawio-workflow-handbook:v1", buildProjection(root,
                "drawio-workflow-handbook", "v1", "drawio-diagram-workflow-handbook-v1.pdf"));
        result.put("drawio-planning-workshop-scan:v1", buildOcrProjection(root,
                "drawio-planning-workshop-scan", "v1", "drawio-planning-workshop-scan-v1.pdf", ocr));
        return new ProjectionSet(Map.copyOf(result));
    }

    /**
     * Mirrors visual selection/crop processing, then OCRs only selected visual pages before final evidence build.
     * Candidate selection remains independent of task answers, anchors, and evaluator assertions.
     */
    private RetrievalProjectionManifest buildVisualProjection(Path root, String source, String version,
                                                              String filename, OcrEnginePort ocr) throws Exception {
        ParsedDocument parsed = new PdfBoxDocumentParser(150).parse(
                root.resolve("fixtures/generated/pdfs").resolve(filename), "application/pdf",
                temporaryDirectory.resolve(source + "-visual"));
        List<CanonicalPage> nativePages = parsed.pages().stream()
                .map(page -> canonicalAssembler().assemble(page.extraction())).toList();
        var nativeStructure = new DocumentStructureBuilder().build(nativePages);
        VisualCandidateSelectionPolicy selectionPolicy = new VisualCandidateSelectionPolicy(12, 0.15, 3);
        var selection = selectionPolicy.select(nativeStructure, parsed.pageCount());
        Set<Integer> selectedVisualPages = selection.selectedCandidates().stream()
                .map(candidate -> candidate.pageNo()).collect(java.util.stream.Collectors.toSet());
        List<CanonicalPage> pages = new ArrayList<>();
        for (ParsedPage page : parsed.pages()) {
            var extraction = page.extraction();
            if (selectedVisualPages.contains(extraction.pageNo())) {
                extraction = extraction.withOcr(ocr.recognize(page.renderedImage(), extraction.pageNo()));
            }
            pages.add(canonicalAssembler().assemble(extraction));
        }
        var structure = new DocumentStructureBuilder().build(pages);
        VisualCropDeriver cropper = new VisualCropDeriver(25_000_000, 10 * 1024 * 1024);
        Map<Integer, ParsedPage> parsedByPage = new HashMap<>();
        parsed.pages().forEach(page -> parsedByPage.put(page.extraction().pageNo(), page));
        List<VisualCropArtifact> crops = new ArrayList<>();
        for (var candidate : selection.selectedCandidates()) {
            ParsedPage page = parsedByPage.get(candidate.pageNo());
            if (page == null) {
                throw new IllegalArgumentException("visual candidate does not reference a parsed page");
            }
            Path renderedImage = page.renderedImage();
            long imageBytes = Files.size(renderedImage);
            if (imageBytes > MAX_RESEARCH_PAGE_ARTIFACT_BYTES) {
                throw new IllegalArgumentException("rendered page image exceeds the research artifact limit");
            }
            byte[] crop = cropper.derive(Files.readAllBytes(renderedImage), candidate);
            String cropHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(crop));
            StoredArtifact artifact = new StoredArtifact("research/" + source + "/visual/"
                    + candidate.candidateId() + ".png", "object-version-1", cropHash, crop.length, "image/png");
            crops.add(new VisualCropArtifact(source + "-page-" + candidate.pageNo(), candidate, artifact));
        }
        List<EvidenceSourcePage> sources = new ArrayList<>();
        for (CanonicalPage page : pages) {
            StoredArtifact artifact = new StoredArtifact("research/" + source + "/page-" + page.pageNo(),
                    "object-version-1", "a".repeat(64), 1, "application/json+gzip");
            sources.add(new EvidenceSourcePage(source + "-page-" + page.pageNo(), page, artifact));
        }
        VisualCropManifest visuals = new VisualCropManifest("visual-crop-manifest-v1", structure.structureHash(),
                selectionPolicy.fingerprint() + ":selected-page-ocr-v1", selection.totalCandidateCount(),
                selection.skippedCandidateCount(), crops);
        EvidenceManifest evidence = new EvidenceUnitBuilder().build("revision-" + source,
                source + ":" + version, structure, sources, visuals);
        return new RetrievalChunkBuilder(RESEARCH_COUNTER).build(evidence);
    }

    /** Uses the worker's OCR boundary before canonicalisation; no fixture answer text is injected. */
    private RetrievalProjectionManifest buildOcrProjection(Path root, String source, String version,
                                                            String filename, OcrEnginePort ocr) throws Exception {
        ParsedDocument parsed = new PdfBoxDocumentParser(150).parse(
                root.resolve("fixtures/generated/pdfs").resolve(filename), "application/pdf",
                temporaryDirectory.resolve(source + "-ocr"));
        OcrSelectionPolicy policy = new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03);
        List<CanonicalPage> pages = new ArrayList<>();
        for (ParsedPage page : parsed.pages()) {
            var extraction = page.extraction();
            if (policy.requiresOcr("application/pdf", extraction.nativeTextQuality(), extraction.rasterRegions())) {
                extraction = extraction.withOcr(ocr.recognize(page.renderedImage(), extraction.pageNo()));
            }
            pages.add(canonicalAssembler().assemble(extraction));
        }
        var structure = new DocumentStructureBuilder().build(pages);
        List<EvidenceSourcePage> sources = new ArrayList<>();
        for (CanonicalPage page : pages) {
            StoredArtifact artifact = new StoredArtifact("research/" + source + "/page-" + page.pageNo(),
                    "object-version-1", "a".repeat(64), 1, "application/json+gzip");
            sources.add(new EvidenceSourcePage(source + "-page-" + page.pageNo(), page, artifact));
        }
        VisualCropManifest visuals = new VisualCropManifest("visual-crop-manifest-v1",
                structure.structureHash(), "research-no-visual-crops", 0, 0, List.of());
        EvidenceManifest evidence = new EvidenceUnitBuilder().build("revision-" + source,
                source + ":" + version, structure, sources, visuals);
        return new RetrievalChunkBuilder(RESEARCH_COUNTER).build(evidence);
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

    private List<IndexedChunk> indexedChunks(String runId, ProjectionSet projections,
                                             ChunkMode chunkMode) {
        List<IndexedChunk> result = new ArrayList<>();
        projections.bySourceVersion().forEach((sourceVersion, manifest) -> manifest.chunks().stream()
                .filter(chunk -> chunk.indexMode() == RetrievalIndexMode.DENSE_AND_LEXICAL)
                .forEach(chunk -> result.add(new IndexedChunk(runId + "_" + result.size(),
                        sourceVersion, chunk, chunkMode.embeddingText(chunk)))));
        return List.copyOf(result);
    }

    private ChunkMode chunkMode() {
        return ChunkMode.fromId(System.getenv().getOrDefault(
                "MATERIAL_RAG_CHUNK_MODE", ChunkMode.FLAT_LEAF.id()));
    }

    private RetrievalMode retrievalMode() {
        return RetrievalMode.fromId(System.getenv().getOrDefault(
                "MATERIAL_RAG_RETRIEVAL_MODE", RetrievalMode.DENSE.id()));
    }

    private ResearchLlmReranker rerankerFromEnvironment() {
        return new ResearchLlmReranker(JSON, new ResearchOpenAiCompletionClient(
                requiredEnvironment("MATERIAL_RAG_RERANKER_BASE_URL"),
                requiredEnvironment("MATERIAL_RAG_RERANKER_COMPLETIONS_PATH"),
                requiredEnvironment("MATERIAL_RAG_RERANKER_API_KEY"), JSON,
                Boolean.parseBoolean(System.getenv().getOrDefault(
                        "MATERIAL_RAG_RERANKER_DISABLE_THINKING", "false"))));
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private List<float[]> embedPassages(PineconeVectorClient client,
                                        List<String> passages) throws InterruptedException {
        List<float[]> result = new ArrayList<>();
        for (int start = 0; start < passages.size(); start += 96) {
            int batchStart = start;
            result.addAll(retryPinecone("embed research passages", () -> client.embed(
                    passages.subList(batchStart, Math.min(batchStart + 96, passages.size())),
                    "passage")));
        }
        return List.copyOf(result);
    }

    private EvaluationMetrics evaluate(PineconeVectorClient client, String namespace,
                                       String tenantKey, List<ResearchCase> cases,
                                       Map<String, ResearchAnchor> anchors,
                                       ProjectionSet projections,
                                       List<IndexedChunk> indexed,
                                       ResearchLlmReranker reranker,
                                       String rerankerModel) throws InterruptedException {
        List<CaseRank> originalRanks = new ArrayList<>();
        Map<RetrievalMode, List<CaseRank>> ranksByMode = new LinkedHashMap<>();
        ranksByMode.put(RetrievalMode.DENSE, originalRanks);
        ranksByMode.put(RetrievalMode.HYBRID_PROJECTION_RRF, new ArrayList<>());
        Map<QueryMode, List<CaseRank>> ranksByQueryMode = new LinkedHashMap<>();
        ranksByQueryMode.put(QueryMode.ORIGINAL, originalRanks);
        ranksByQueryMode.put(QueryMode.EVIDENCE_FOCUSED, new ArrayList<>());
        Map<PostprocessMode, List<CaseRank>> ranksByPostprocessMode = new LinkedHashMap<>();
        ranksByPostprocessMode.put(PostprocessMode.RANKED_RAW, originalRanks);
        ranksByPostprocessMode.put(PostprocessMode.EVIDENCE_DEDUP, new ArrayList<>());
        ranksByPostprocessMode.put(PostprocessMode.SOURCE_DIVERSITY, new ArrayList<>());
        Map<RerankerMode, List<CaseRank>> ranksByRerankerMode = new LinkedHashMap<>();
        ranksByRerankerMode.put(RerankerMode.NONE, originalRanks);
        ranksByRerankerMode.put(RerankerMode.LLM_LISTWISE, new ArrayList<>());
        List<ResearchLlmReranker.Result> rerankerResults = new ArrayList<>();
        Map<String, IndexedChunk> indexedByVectorId = indexed.stream().collect(
                java.util.stream.Collectors.toMap(IndexedChunk::vectorId, value -> value));
        Map<QueryMode, List<float[]>> queryVectors = new LinkedHashMap<>();
        for (QueryMode mode : QueryMode.values()) {
            queryVectors.put(mode, embedQueries(client,
                    cases.stream().map(value -> mode.query(value.query())).toList()));
        }
        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            ResearchCase researchCase = cases.get(caseIndex);
            List<EvidenceRequirement> requirements = researchCase.requiredEvidenceGroups().stream()
                    .flatMap(group -> group.evidence().stream()).toList();
            List<ResearchAnchor> required = requirements.stream()
                    .map(requirement -> anchors.get(requirement.anchorId())).toList();
            Set<String> goldSourceVersions = required.stream().map(ResearchAnchor::sourceVersion)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> mountedSourceVersions = researchCase.mountedSourceVersions().isEmpty()
                    ? goldSourceVersions : Set.copyOf(researchCase.mountedSourceVersions());
            if (!mountedSourceVersions.containsAll(goldSourceVersions)) {
                throw new IllegalStateException("Gold source is outside the mounted chartbook: "
                        + researchCase.caseId());
            }
            Map<String, Set<String>> requiredGoldVectorIds = new LinkedHashMap<>();
            Map<String, List<String>> fixedGoldChunkIdsByAnchor = new LinkedHashMap<>();
            for (EvidenceRequirement requirement : requirements) {
                ResearchAnchor anchor = anchors.get(requirement.anchorId());
                String sourceVersion = anchor.sourceVersion();
                Set<String> fixedGoldChunkIds = goldChunkIds(
                        projections.bySourceVersion().get(sourceVersion), anchor);
                fixedGoldChunkIdsByAnchor.put(requirement.anchorId(),
                        fixedGoldChunkIds.stream().sorted().toList());
                requiredGoldVectorIds.put(requirement.anchorId(), fixedGoldVectorIds(
                        indexed, sourceVersion, fixedGoldChunkIds));
            }
            int queryIndex = caseIndex;
            List<String> densePool = retryPinecone("query original research vectors", () -> client.query(
                    namespace, queryVectors.get(QueryMode.ORIGINAL).get(queryIndex), 80,
                    researchFilter(tenantKey, mountedSourceVersions)));
            List<String> denseMatches = densePool.stream().limit(40).toList();
            List<String> rewrittenPool = retryPinecone("query rewritten research vectors", () -> client.query(
                    namespace, queryVectors.get(QueryMode.EVIDENCE_FOCUSED).get(queryIndex), 80,
                    researchFilter(tenantKey, mountedSourceVersions)));
            List<String> rewrittenMatches = rewrittenPool.stream().limit(40).toList();
            List<String> denseChunkIds = denseMatches.stream()
                    .map(indexedByVectorId::get).map(value -> value.chunk().chunkId()).toList();
            List<String> lexicalChunkIds = ResearchHybridRanker.lexicalRank(researchCase.query(),
                    mountedSourceVersions.stream().sorted()
                            .flatMap(source -> projections.bySourceVersion().get(source)
                                    .lexicalProjections().stream()).toList());
            List<String> hybridChunkIds = ResearchHybridRanker.fuse(
                    lexicalChunkIds, denseChunkIds, 40);
            Map<String, IndexedChunk> indexedByChunkId = indexed.stream()
                    .filter(value -> mountedSourceVersions.contains(value.sourceVersion()))
                    .collect(java.util.stream.Collectors.toMap(
                            value -> value.chunk().chunkId(), value -> value, (left, right) -> left));
            List<CandidateResult> denseCandidates = candidateResults(denseMatches, indexedByVectorId);
            // Preserve the exact dedup input so the paired postprocess comparison is replayable.
            List<CandidateResult> densePoolCandidates = candidateResults(densePool, indexedByVectorId);
            List<CandidateResult> lexicalCandidates = candidateResults(lexicalChunkIds.stream()
                    .map(indexedByChunkId::get).map(IndexedChunk::vectorId).toList(), indexedByVectorId);
            CaseRank originalRank = caseRank(researchCase, denseMatches, requiredGoldVectorIds,
                    fixedGoldChunkIdsByAnchor, indexedByVectorId, denseCandidates, lexicalCandidates,
                    densePoolCandidates);
            originalRanks.add(originalRank);
            List<String> hybridMatches = hybridChunkIds.stream().map(indexedByChunkId::get)
                    .map(IndexedChunk::vectorId).toList();
            ranksByMode.get(RetrievalMode.HYBRID_PROJECTION_RRF).add(caseRank(
                    researchCase, hybridMatches, requiredGoldVectorIds, fixedGoldChunkIdsByAnchor,
                    indexedByVectorId, denseCandidates, lexicalCandidates, densePoolCandidates));
            List<CandidateResult> rewrittenCandidates = candidateResults(
                    rewrittenMatches, indexedByVectorId);
            List<CandidateResult> rewrittenPoolCandidates = candidateResults(
                    rewrittenPool, indexedByVectorId);
            ranksByQueryMode.get(QueryMode.EVIDENCE_FOCUSED).add(caseRank(
                    researchCase, rewrittenMatches, requiredGoldVectorIds, fixedGoldChunkIdsByAnchor,
                    indexedByVectorId, rewrittenCandidates, List.of(), rewrittenPoolCandidates));
            List<String> deduplicatedMatches = ResearchEvidenceDeduplicator.deduplicate(
                    densePool.stream().map(indexedByVectorId::get).map(value ->
                            new ResearchEvidenceDeduplicator.Candidate(
                                    value.vectorId(), value.chunk().citable(),
                                    value.chunk().retrievalTextSha256(), value.chunk().evidenceMappings().stream()
                                    .map(RetrievalEvidenceMapping::evidenceId)
                                    .collect(java.util.stream.Collectors.toUnmodifiableSet()))).toList(), 40);
            ranksByPostprocessMode.get(PostprocessMode.EVIDENCE_DEDUP).add(caseRank(
                    researchCase, deduplicatedMatches, requiredGoldVectorIds, fixedGoldChunkIdsByAnchor,
                    indexedByVectorId, denseCandidates, List.of(), densePoolCandidates));
            List<String> diversifiedMatches = ResearchSourceDiversifier.diversify(
                    densePool.stream().map(indexedByVectorId::get).map(value ->
                            new ResearchSourceDiversifier.Candidate(
                                    value.vectorId(), value.sourceVersion(), value.chunk().citable(),
                                    value.chunk().retrievalTextSha256(), value.chunk().evidenceMappings().stream()
                                    .map(RetrievalEvidenceMapping::evidenceId)
                                    .collect(java.util.stream.Collectors.toUnmodifiableSet()))).toList(),
                    40, 10, 4);
            ranksByPostprocessMode.get(PostprocessMode.SOURCE_DIVERSITY).add(caseRank(
                    researchCase, diversifiedMatches, requiredGoldVectorIds, fixedGoldChunkIdsByAnchor,
                    indexedByVectorId, denseCandidates, List.of(), densePoolCandidates));
            if (reranker != null) {
                List<ResearchLlmReranker.Candidate> rerankerCandidates = denseCandidates.stream()
                        .map(candidate -> new ResearchLlmReranker.Candidate(candidate.vectorId(),
                                candidate.sourceVersion(), indexedByVectorId.get(candidate.vectorId())
                                .chunk().retrievalText())).toList();
                ResearchLlmReranker.Result rerankerResult = reranker.rerank(researchCase.query(),
                        rerankerCandidates, rerankerModel);
                rerankerResults.add(rerankerResult);
                List<String> rerankedMatches = rerankerResult.vectorIds();
                ranksByRerankerMode.get(RerankerMode.LLM_LISTWISE).add(caseRank(
                        researchCase, rerankedMatches, requiredGoldVectorIds, fixedGoldChunkIdsByAnchor,
                        indexedByVectorId, denseCandidates, List.of(), densePoolCandidates));
            }
            if ((caseIndex + 1) % 10 == 0 || caseIndex + 1 == cases.size()) {
                System.out.printf("Research cases evaluated: %d/%d%n", caseIndex + 1, cases.size());
            }
        }
        Map<RetrievalMode, DenseMetrics> retrievalMetrics = new LinkedHashMap<>();
        ranksByMode.forEach((mode, ranks) -> retrievalMetrics.put(mode, summarizeMetrics(ranks)));
        Map<QueryMode, DenseMetrics> queryMetrics = new LinkedHashMap<>();
        ranksByQueryMode.forEach((mode, ranks) -> queryMetrics.put(mode, summarizeMetrics(ranks)));
        Map<PostprocessMode, DenseMetrics> postprocessMetrics = new LinkedHashMap<>();
        ranksByPostprocessMode.forEach((mode, ranks) ->
                postprocessMetrics.put(mode, summarizeMetrics(ranks)));
        Map<RerankerMode, DenseMetrics> rerankerMetrics = new LinkedHashMap<>();
        ranksByRerankerMode.forEach((mode, ranks) -> {
            if (mode == RerankerMode.NONE || reranker != null) {
                rerankerMetrics.put(mode, summarizeMetrics(ranks));
            }
        });
        return new EvaluationMetrics(Map.copyOf(retrievalMetrics), Map.copyOf(queryMetrics),
                Map.copyOf(postprocessMetrics), Map.copyOf(rerankerMetrics),
                new RerankerUsage(rerankerResults.size(), (int) rerankerResults.stream()
                        .filter(ResearchLlmReranker.Result::modelOutputAccepted).count(),
                        rerankerResults.stream().mapToLong(ResearchLlmReranker.Result::latencyMillis).sum(),
                        rerankerResults.stream().mapToInt(ResearchLlmReranker.Result::promptTokens).sum(),
                        rerankerResults.stream().mapToInt(ResearchLlmReranker.Result::completionTokens).sum()));
    }

    private Map<String, Object> researchFilter(String tenantKey, Set<String> mountedSourceVersions) {
        Map<String, Object> versionFilter = mountedSourceVersions.size() == 1
                ? Map.of("$eq", mountedSourceVersions.iterator().next())
                : Map.of("$in", mountedSourceVersions.stream().sorted().toList());
        return Map.of("$and", List.of(
                Map.of("tenant_key", Map.of("$eq", tenantKey)),
                Map.of("version_id", versionFilter)));
    }

    private List<float[]> embedQueries(PineconeVectorClient client,
                                       List<String> queries) throws InterruptedException {
        List<float[]> result = new ArrayList<>();
        for (int start = 0; start < queries.size(); start += 3) {
            int batchStart = start;
            result.addAll(retryPinecone("embed research queries", () -> client.embed(
                    queries.subList(batchStart, Math.min(batchStart + 3, queries.size())), "query")));
        }
        return List.copyOf(result);
    }

    private CaseRank caseRank(ResearchCase researchCase, List<String> matches,
                              Map<String, Set<String>> requiredGoldVectorIds,
                              Map<String, List<String>> fixedGoldChunkIdsByAnchor,
                              Map<String, IndexedChunk> indexedByVectorId,
                              List<CandidateResult> denseCandidates,
                              List<CandidateResult> lexicalCandidates,
                              List<CandidateResult> retrievalPoolCandidates) {
        return new CaseRank(researchCase, completeEvidenceRank(
                matches, researchCase.requiredEvidenceGroups(), requiredGoldVectorIds),
                isMappable(researchCase.requiredEvidenceGroups(), requiredGoldVectorIds),
                Map.copyOf(fixedGoldChunkIdsByAnchor), candidateResults(matches, indexedByVectorId),
                denseCandidates, lexicalCandidates, retrievalPoolCandidates);
    }

    private DenseMetrics summarizeMetrics(List<CaseRank> ranks) {
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
                value.researchCase().goldAnchorIds(), value.fixedGoldChunkIdsByAnchor(),
                value.mappable(), value.candidates(), value.denseCandidates(),
                value.lexicalCandidates(), value.retrievalPoolCandidates(),
                value.researchCase().mountedSourceVersions(),
                value.researchCase().unmountedSourceVersions(),
                value.researchCase().goldSourceVersions())).toList();
        return new DenseMetrics((double) mappedRanks.size() / ranks.size(), mappedRanks.size(),
                total.recallAt1(), total.recallAt5(), total.recallAt10(), total.recallAt40(),
                total.mrrAt10(), conditional, misses, List.copyOf(slices), weak, caseResults);
    }

    private List<CandidateResult> candidateResults(List<String> vectorIds,
                                                   Map<String, IndexedChunk> indexedByVectorId) {
        List<CandidateResult> candidates = new ArrayList<>();
        for (int index = 0; index < vectorIds.size(); index++) {
            IndexedChunk candidate = indexedByVectorId.get(vectorIds.get(index));
            candidates.add(new CandidateResult(index + 1, vectorIds.get(index),
                    candidate == null ? "unknown" : candidate.sourceVersion(),
                    candidate == null ? "unknown" : candidate.chunk().chunkId(),
                    candidate == null ? "unknown" : candidate.chunk().pageId(),
                    candidate == null ? "UNKNOWN" : candidate.chunk().modality().name(),
                    candidate == null ? "" : candidate.chunk().retrievalText()));
        }
        return List.copyOf(candidates);
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

    private Set<String> fixedGoldVectorIds(List<IndexedChunk> indexed, String sourceVersion,
                                           Set<String> fixedGoldChunkIds) {
        return indexed.stream()
                .filter(value -> value.sourceVersion().equals(sourceVersion)
                        && fixedGoldChunkIds.contains(value.chunk().chunkId()))
                .map(IndexedChunk::vectorId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
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
                List<String> allowedSources = stringList(value, "allowedSourceVersions");
                result.add(new ResearchCase(value.path("caseId").asText(), value.path("category").asText(),
                        value.path("primaryCategory").asText(), value.path("language").asText(),
                        value.path("query").asText(),
                        value.path("split").asText("development"), value.path("answerable").asBoolean(),
                        goldAnchorIds, groups, allowedSources, List.of(), allowedSources));
            }
        }
        return List.copyOf(result);
    }

    private List<ResearchCase> chartbookCases(Path root) throws Exception {
        JsonNode values = JSON.readTree(
                root.resolve("fixtures/generated/e4-chartbook-cases.json").toFile()).path("cases");
        List<ResearchCase> result = new ArrayList<>();
        for (JsonNode value : values) {
            List<String> goldAnchorIds = stringList(value, "goldAnchorIds");
            result.add(new ResearchCase(
                    value.path("caseId").asText(), value.path("category").asText(),
                    value.path("primaryCategory").asText(), value.path("language").asText(),
                    value.path("query").asText(), value.path("split").asText(),
                    value.path("answerable").asBoolean(), goldAnchorIds,
                    requiredEvidenceGroups(value, goldAnchorIds),
                    stringList(value, "mountedSourceVersions"),
                    stringList(value, "unmountedSourceVersions"),
                    stringList(value, "goldSourceVersions")));
        }
        return List.copyOf(result);
    }

    /** Loads only model-visible task requests; evaluator anchors remain outside this retrieval input. */
    private List<ResearchCase> drawioGenerationCases(Path root) throws Exception {
        JsonNode fixture = JSON.readTree(root.resolve("fixtures/drawio-generation-tasks-v2.json").toFile());
        List<String> mounted = stringList(fixture, "developmentChartbookSourceVersions").stream()
                .sorted().toList();
        if (mounted.isEmpty()) {
            throw new IllegalArgumentException("draw.io generation fixture has no Development chartbook");
        }
        Set<String> noRetrieval = drawioGenerationNoRetrievalTaskIds(root);
        List<ResearchCase> result = new ArrayList<>();
        for (JsonNode task : fixture.path("tasks")) {
            if (!"development".equals(task.path("split").asText())) {
                continue;
            }
            if (noRetrieval.contains(task.path("taskId").asText())) {
                continue;
            }
            // Selection validation is distinct from retrieval ranking and evaluator citation assertions.
            SelectedMaterialVersionValidator.requireMounted(task.path("selectedMaterialVersion").asText(), mounted);
            result.add(new ResearchCase(task.path("taskId").asText(), "drawio_generation",
                    task.path("type").asText(), "mixed", task.path("request").asText(),
                    "development", true, List.of(), List.of(), mounted, List.of(), List.of()));
        }
        return List.copyOf(result);
    }

    /** Keeps explicitly structural-only tasks out of retrieval while preserving their paired empty contexts. */
    private Set<String> drawioGenerationNoRetrievalTaskIds(Path root) throws Exception {
        JsonNode fixture = JSON.readTree(root.resolve("fixtures/drawio-generation-tasks-v2.json").toFile());
        Set<String> developmentIds = new HashSet<>();
        for (JsonNode task : fixture.path("tasks")) {
            if ("development".equals(task.path("split").asText())) {
                developmentIds.add(task.path("taskId").asText());
            }
        }
        Set<String> noRetrieval = Set.copyOf(stringList(fixture, "developmentNoRetrievalTaskIds"));
        if (!developmentIds.containsAll(noRetrieval)) {
            throw new IllegalArgumentException("no-retrieval task is not in the Development fixture");
        }
        return noRetrieval;
    }


    /** Keeps promotion metrics tied to draw.io agent documents without crossing frozen families. */
    private List<ResearchCase> drawioCoreCases(Path root, String split) throws Exception {
        Set<String> families = switch (split) {
            case "development" -> Set.of("drawio-agent-architecture-blueprint",
                    "drawio-diagram-workflow-handbook", "drawio-planning-workshop-scan");
            case "validation" -> Set.of("drawio-collaboration-governance");
            case "holdout" -> Set.of("drawio-agent-recovery-runbook");
            default -> throw new IllegalArgumentException("Unknown drawio-core split: " + split);
        };
        Set<String> caseIds = new HashSet<>();
        try (Stream<String> lines = Files.lines(root.resolve("fixtures/generated/cases.jsonl"))) {
            for (String line : lines.filter(value -> !value.isBlank()).toList()) {
                JsonNode value = JSON.readTree(line);
                if (families.contains(value.path("documentFamily").asText())) {
                    caseIds.add(value.path("caseId").asText());
                }
            }
        }
        return cases(root).stream().filter(value -> caseIds.contains(value.caseId())).toList();
    }

    private List<String> stringList(JsonNode value, String field) {
        JsonNode values = value.path(field);
        if (!values.isArray()) return List.of();
        return JSON.convertValue(values,
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
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
            if (attempt > 0 && attempt % 20 == 0) {
                System.out.printf("Index visibility checks: %d/%d%n", attempt, maxAttempts);
            }
            if (!retryPinecone("check research vector visibility", () ->
                    allExisting(client, namespace, vectorIds))) {
                Thread.sleep(500L);
                continue;
            }
            boolean searchable = true;
            int sampleCount = Math.min(12, indexed.size());
            for (int sample = 0; sample < sampleCount; sample++) {
                int index = sampleCount == 1 ? 0
                        : sample * (indexed.size() - 1) / (sampleCount - 1);
                if (!retryPinecone("check research searchability", () -> client.query(
                        namespace, vectors.get(index), 1,
                        Map.of("tenant_key", Map.of("$eq", tenantKey))))
                        .contains(indexed.get(index).vectorId())) {
                    searchable = false;
                    break;
                }
            }
            if (searchable) return;
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Controlled PDF vectors were not searchable after "
                + maxAttempts + " visibility checks");
    }

    private void waitUntilDeleted(PineconeVectorClient client, String namespace,
                                  List<String> vectorIds) throws Exception {
        if (vectorIds.isEmpty()) return;
        int maxAttempts = Integer.parseInt(
                System.getenv().getOrDefault("MATERIAL_RAG_DELETE_WAIT_ATTEMPTS", "20"));
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (retryPinecone("check research vector deletion", () ->
                    noneExisting(client, namespace, vectorIds))) return;
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

    private <T> T retryPinecone(String operation, Supplier<T> action) throws InterruptedException {
        int attempts = Integer.parseInt(System.getenv().getOrDefault(
                "MATERIAL_RAG_TRANSIENT_RETRY_ATTEMPTS", "5"));
        return retryPinecone(operation, attempts, action);
    }

    private <T> T retryPinecone(String operation, int attempts,
                                Supplier<T> action) throws InterruptedException {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return action.get();
            } catch (RetryableRetrievalException failure) {
                if (attempt == attempts) throw failure;
                long providerDelay = failure.retryAfter() == null
                        ? 1_000L << Math.min(attempt - 1, 4)
                        : failure.retryAfter().toMillis();
                long delay = Math.max(0L, Math.min(providerDelay, 30_000L));
                System.out.printf("Transient Pinecone failure during %s; retry %d/%d in %dms%n",
                        operation, attempt + 1, attempts, delay);
                Thread.sleep(delay);
            }
        }
        throw new IllegalStateException("Transient Pinecone retry loop exhausted unexpectedly");
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
                                List<RequiredEvidenceGroup> requiredEvidenceGroups,
                                List<String> mountedSourceVersions,
                                List<String> unmountedSourceVersions,
                                List<String> goldSourceVersions) { }

    private record EvidenceRequirement(String anchorId, int grade, int minimumGrade) { }

    private record RequiredEvidenceGroup(String groupId, EvidenceGroupOperator operator,
                                         List<EvidenceRequirement> evidence) { }

    private enum EvidenceGroupOperator { ANY, ALL_PARTS }

    private record OpenCase(String caseId, String sourceId, int page, String language,
                            String query, String goldMatch) { }

    private record IndexedChunk(String vectorId, String sourceVersion, RetrievalChunkProjection chunk,
                                String embeddingText) { }

    private enum ChunkMode {
        FLAT_LEAF("flat-leaf-v1", "flat-leaf-v1:retrieval-text"),
        PARENT_CONTEXT_500("parent-context-500-v1",
                "parent-context-500-v1:existing-neighbor-window-max500:leaf-fallback");

        private final String id;
        private final String fingerprint;

        ChunkMode(String id, String fingerprint) {
            this.id = id;
            this.fingerprint = fingerprint;
        }

        String id() { return id; }

        String fingerprint() { return fingerprint; }

        String embeddingText(RetrievalChunkProjection chunk) {
            if (this == PARENT_CONTEXT_500 && chunk.parentContext() != null
                    && RESEARCH_COUNTER.count(chunk.parentContext()) <= 500) {
                return chunk.parentContext();
            }
            return chunk.retrievalText();
        }

        static ChunkMode fromId(String id) {
            return Stream.of(values()).filter(value -> value.id.equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown MATERIAL_RAG_CHUNK_MODE: " + id));
        }
    }

    private enum RetrievalMode {
        DENSE("dense-v1"),
        HYBRID_PROJECTION_RRF("hybrid-projection-rrf-v1");

        private final String id;

        RetrievalMode(String id) {
            this.id = id;
        }

        String id() { return id; }

        static RetrievalMode fromId(String id) {
            return Stream.of(values()).filter(value -> value.id.equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown MATERIAL_RAG_RETRIEVAL_MODE: " + id));
        }
    }

    private enum QueryMode {
        ORIGINAL("original-v1"),
        EVIDENCE_FOCUSED("evidence-focused-v1");

        private final String id;

        QueryMode(String id) {
            this.id = id;
        }

        String id() { return id; }

        String query(String original) {
            return this == EVIDENCE_FOCUSED ? ResearchQueryRewriter.rewrite(original) : original;
        }
    }

    private enum PostprocessMode {
        RANKED_RAW("ranked-raw-v1"),
        EVIDENCE_DEDUP("evidence-dedup-v1"),
        SOURCE_DIVERSITY("source-diversity-v1");

        private final String id;

        PostprocessMode(String id) {
            this.id = id;
        }

        String id() { return id; }
    }

    private enum RerankerMode {
        NONE("none-v1"),
        LLM_LISTWISE("llm-listwise-v1");

        private final String id;

        RerankerMode(String id) {
            this.id = id;
        }

        String id() { return id; }
    }

    private record CaseRank(ResearchCase researchCase, int rank, boolean mappable,
                            Map<String, List<String>> fixedGoldChunkIdsByAnchor,
                            List<CandidateResult> candidates,
                            List<CandidateResult> denseCandidates,
                            List<CandidateResult> lexicalCandidates,
                            List<CandidateResult> retrievalPoolCandidates) { }

    private record CandidateResult(int rank, String vectorId, String sourceVersion, String chunkId,
                                   String pageId, String modality, String retrievalText) { }

    private record CaseResult(String caseId, int rank, String category, String primaryCategory,
                              String language, List<String> goldAnchorIds,
                              Map<String, List<String>> fixedGoldChunkIdsByAnchor, boolean mappable,
                              List<CandidateResult> candidates, List<CandidateResult> denseCandidates,
                              List<CandidateResult> lexicalCandidates,
                              List<CandidateResult> retrievalPoolCandidates,
                              List<String> mountedSourceVersions,
                              List<String> unmountedSourceVersions,
                              List<String> goldSourceVersions) { }

    private record SliceMetric(String label, int count, double recallAt1, double recallAt5,
                               double recallAt10, double recallAt40, double mrrAt10) { }

    private record DenseMetrics(double mappingRate, int mappableCaseCount, double recallAt1,
                                double recallAt5, double recallAt10, double recallAt40, double mrrAt10,
                                SliceMetric conditional, List<String> misses,
                                List<SliceMetric> slices, List<String> weakCases,
                                List<CaseResult> caseResults) { }

    private record EmbeddingProfile(int p50, int p95, int max) { }

    private record RerankerUsage(int callCount, int acceptedOutputCount, long totalLatencyMillis,
                                 int totalPromptTokens, int totalCompletionTokens) { }

    private record EvaluationMetrics(Map<RetrievalMode, DenseMetrics> retrieval,
                                     Map<QueryMode, DenseMetrics> query,
                                     Map<PostprocessMode, DenseMetrics> postprocess,
                                     Map<RerankerMode, DenseMetrics> reranker,
                                     RerankerUsage rerankerUsage) { }

    private record ExperimentResult(String runId, String canonicalFingerprint,
                                    EvaluationMetrics evaluation, int chunkCount,
                                    EmbeddingProfile embeddingTokenProfile) {
        DenseMetrics metrics(RetrievalMode mode) {
            return evaluation.retrieval().get(mode);
        }

        DenseMetrics metrics(QueryMode mode) {
            return evaluation.query().get(mode);
        }

        DenseMetrics metrics(PostprocessMode mode) {
            return evaluation.postprocess().get(mode);
        }

        DenseMetrics metrics(RerankerMode mode) {
            return evaluation.reranker().get(mode);
        }

        RerankerUsage rerankerUsage() {
            return evaluation.rerankerUsage();
        }
    }
}
