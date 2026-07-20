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
import java.util.ArrayList;
import java.util.HashMap;
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

    @TempDir
    Path temporaryDirectory;

    @Test
    void generatedCorpusShouldExposeNativeAndOcrBoundariesBeforeRetrieval() throws Exception {
        Path root = researchRoot();
        ProjectionSet projections = buildControlledProjections(root);
        assertFalse(projections.bySourceVersion().values().stream()
                .flatMap(value -> value.chunks().stream()).toList().isEmpty());

        List<ResearchAnchor> anchors = anchors(root).stream()
                .filter(anchor -> Set.of("text", "table").contains(anchor.modality())).toList();
        List<String> unmappable = anchors.stream().filter(anchor -> goldChunkIds(
                projections.bySourceVersion().get(anchor.sourceVersion()), anchor).isEmpty())
                .map(ResearchAnchor::anchorId).toList();
        assertTrue(unmappable.isEmpty(), "Gold anchors lost before retrieval: " + unmappable);

        ParsedDocument scanned = new PdfBoxDocumentParser(150).parse(
                root.resolve("fixtures/generated/pdfs/scanned-operations-cards.pdf"),
                "application/pdf", temporaryDirectory.resolve("scanned"));
        OcrSelectionPolicy policy = new OcrSelectionPolicy(40, 0.10, 0.20, 0.01, 0.03);
        assertEquals(3, scanned.pageCount());
        assertTrue(scanned.pages().stream().allMatch(page -> page.extraction().nativeBlocks().isEmpty()));
        assertTrue(scanned.pages().stream().allMatch(page -> policy.requiresOcr("application/pdf",
                page.extraction().nativeTextQuality(), page.extraction().rasterRegions())));
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
        ProjectionSet projections = buildControlledProjections(root);
        Map<String, ResearchAnchor> anchors = new HashMap<>();
        anchors(root).forEach(anchor -> anchors.put(anchor.anchorId(), anchor));
        List<ResearchCase> cases = cases(root).stream()
                .filter(ResearchCase::answerable)
                .filter(value -> {
                    ResearchAnchor anchor = anchors.get(value.goldAnchorId());
                    return anchor != null && Set.of("text", "table").contains(anchor.modality());
                }).toList();
        assertFalse(cases.isEmpty());

        PineconeVectorClient client = new PineconeVectorClient(
                apiKey, indexHost, "multilingual-e5-large", 1024, JSON);
        ExperimentResult result = runDenseExperiment(
                "controlled", client, namespace, projections, cases, anchors);
        report("Controlled PDF", result);
        assertTrue(result.metrics().recallAt10() >= 0.90, "Dense PDF Recall@10 fell below the text gate");
        assertTrue(result.metrics().recallAt40() >= 0.95, "Dense PDF candidate Recall@40 fell below the gate");
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
                    "open_pdf", value.goldMatch(), value.page()));
            cases.add(new ResearchCase(value.caseId(), "open_pdf", value.language(), value.query(),
                    true, value.caseId()));
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
                            value.goldMatch(), value.page())).isEmpty()).count();
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
            return new ExperimentResult(evaluate(
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
                "%s dense pipeline: Recall@1=%.4f Recall@5=%.4f Recall@10=%.4f "
                        + "Recall@40=%.4f MRR@10=%.4f chunks=%d%n",
                label, metrics.recallAt1(), metrics.recallAt5(), metrics.recallAt10(),
                metrics.recallAt40(), metrics.mrrAt10(), result.chunkCount());
        if (!metrics.misses().isEmpty()) System.out.println(label + " dense misses: " + metrics.misses());
        metrics.slices().forEach(slice -> System.out.printf(Locale.ROOT,
                "%s slice %-24s n=%d R@1=%.4f R@5=%.4f R@10=%.4f MRR@10=%.4f%n",
                label, slice.label(), slice.count(), slice.recallAt1(), slice.recallAt5(),
                slice.recallAt10(), slice.mrrAt10()));
        if (!metrics.weakCases().isEmpty()) {
            System.out.println(label + " cases outside top 5: " + metrics.weakCases());
        }
    }

    private ProjectionSet buildControlledProjections(Path root) throws Exception {
        Map<String, RetrievalProjectionManifest> result = new LinkedHashMap<>();
        result.put("controlled-guide-v1:v1", buildProjection(root,
                "controlled-guide-v1", "v1", "controlled-operations-guide-v1.pdf"));
        result.put("controlled-guide-v2:v2", buildProjection(root,
                "controlled-guide-v2", "v2", "controlled-operations-guide-v2.pdf"));
        return new ProjectionSet(Map.copyOf(result));
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
        CanonicalPageAssembler assembler = new CanonicalPageAssembler(0.70);
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
        for (ResearchCase researchCase : cases) {
            ResearchAnchor anchor = anchors.get(researchCase.goldAnchorId());
            Set<String> goldChunkIds = goldChunkIds(
                    projections.bySourceVersion().get(anchor.sourceVersion()), anchor);
            Set<String> goldVectorIds = indexed.stream()
                    .filter(value -> value.sourceVersion().equals(anchor.sourceVersion())
                            && goldChunkIds.contains(value.chunk().chunkId()))
                    .map(IndexedChunk::vectorId).collect(java.util.stream.Collectors.toSet());
            float[] query = client.embedOne(researchCase.query(), "query");
            List<String> matches = client.query(namespace, query, 40, Map.of("$and", List.of(
                    Map.of("tenant_key", Map.of("$eq", tenantKey)),
                    Map.of("version_id", Map.of("$eq", anchor.sourceVersion())))));
            int rank = firstGoldRank(matches, goldVectorIds);
            ranks.add(new CaseRank(researchCase, rank < 0 ? 0 : rank + 1));
        }
        SliceMetric total = summarize("all", ranks);
        List<SliceMetric> slices = new ArrayList<>();
        ranks.stream().map(value -> "language:" + value.researchCase().language()).distinct().sorted()
                .forEach(label -> slices.add(summarize(label, ranks.stream().filter(value ->
                        label.equals("language:" + value.researchCase().language())).toList())));
        ranks.stream().map(value -> "category:" + value.researchCase().category()).distinct().sorted()
                .forEach(label -> slices.add(summarize(label, ranks.stream().filter(value ->
                        label.equals("category:" + value.researchCase().category())).toList())));
        List<String> misses = ranks.stream().filter(value -> value.rank() == 0)
                .map(value -> value.researchCase().caseId()).toList();
        List<String> weak = ranks.stream().filter(value -> value.rank() == 0 || value.rank() > 5)
                .map(value -> value.researchCase().caseId() + ":rank=" + value.rank()
                        + ":anchor=" + value.researchCase().goldAnchorId()).toList();
        return new DenseMetrics(total.recallAt1(), total.recallAt5(), total.recallAt10(),
                total.recallAt40(), total.mrrAt10(), misses, List.copyOf(slices), weak);
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

    private int firstGoldRank(List<String> matches, Set<String> gold) {
        for (int index = 0; index < matches.size(); index++) {
            if (gold.contains(matches.get(index))) return index;
        }
        return -1;
    }

    private Set<String> goldChunkIds(RetrievalProjectionManifest manifest, ResearchAnchor anchor) {
        Set<String> exact = exactGoldChunkIds(manifest, anchor.goldMatch());
        if (!exact.isEmpty() || manifest == null || anchor.pageNo() <= 0) return exact;
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
                    value.path("goldMatch").asText(), value.path("page").asInt()));
        }
        return List.copyOf(result);
    }

    private List<ResearchCase> cases(Path root) throws Exception {
        List<ResearchCase> result = new ArrayList<>();
        try (Stream<String> lines = Files.lines(root.resolve("fixtures/generated/cases.jsonl"))) {
            for (String line : lines.filter(value -> !value.isBlank()).toList()) {
                JsonNode value = JSON.readTree(line);
                JsonNode gold = value.path("goldAnchorIds");
                result.add(new ResearchCase(value.path("caseId").asText(), value.path("category").asText(),
                        value.path("language").asText(), value.path("query").asText(),
                        value.path("answerable").asBoolean(), gold.isEmpty() ? null : gold.get(0).asText()));
            }
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
        for (int attempt = 0; attempt < 24; attempt++) {
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
        throw new IllegalStateException("Controlled PDF vectors were not searchable within 12 seconds");
    }

    private void waitUntilDeleted(PineconeVectorClient client, String namespace,
                                  List<String> vectorIds) throws Exception {
        if (vectorIds.isEmpty()) return;
        for (int attempt = 0; attempt < 20; attempt++) {
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
                                  String modality, String goldMatch, int pageNo) {
        String sourceVersion() { return source + ":" + version; }
    }

    private record ResearchCase(String caseId, String category, String language, String query,
                                boolean answerable, String goldAnchorId) { }

    private record OpenCase(String caseId, String sourceId, int page, String language,
                            String query, String goldMatch) { }

    private record IndexedChunk(String vectorId, String sourceVersion, RetrievalChunkProjection chunk) { }

    private record CaseRank(ResearchCase researchCase, int rank) { }

    private record SliceMetric(String label, int count, double recallAt1, double recallAt5,
                               double recallAt10, double recallAt40, double mrrAt10) { }

    private record DenseMetrics(double recallAt1, double recallAt5, double recallAt10,
                                double recallAt40, double mrrAt10, List<String> misses,
                                List<SliceMetric> slices, List<String> weakCases) { }

    private record ExperimentResult(DenseMetrics metrics, int chunkCount) { }
}
