package org.zipp.ai.test.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryQueryPort;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.AutoMemoryVector;
import org.zipp.ai.application.memory.AutoMemoryVectorDocument;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchQuery;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.context.AutoMemoryContextHydrationPort;
import org.zipp.ai.application.turn.context.AutoMemoryContextQuery;
import org.zipp.ai.application.turn.context.AutoMemoryContextSelection;
import org.zipp.ai.application.turn.context.AutoMemoryContextSelector;
import org.zipp.ai.infrastructure.adapter.vector.PineconeAutoMemoryVectorStoreAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One-pass, opt-in V1.7 retrieval evaluation over a frozen synthetic holdout. */
@EnabledIfEnvironmentVariable(
        named = "AUTO_MEMORY_CONTEXT_EVALUATION_ENABLED",
        matches = "true")
class AutoMemoryContextSelectionLiveEvaluationTest {
    private static final String HOLDOUT_SHA256 =
            "18da2d6db1cf1bc7827c03c6f15260ec0b2e7b8d742cea5edcd2baf2bf10f64a";
    private static final String OWNER = "auto-memory-v17-eval-owner";
    private static final String CHARTBOOK = "auto-memory-v17-eval-book";
    private static final Instant OLD = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void frozenHoldoutComparesSemanticSelectionWithSqlWithoutBusinessWrites() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path holdoutPath = holdoutPath();
        byte[] holdoutBytes = Files.readAllBytes(holdoutPath);
        assertEquals(HOLDOUT_SHA256, sha256(holdoutBytes),
                "the V1.7 holdout is frozen; create a new version instead of tuning it");
        Cohort cohort = mapper.readValue(holdoutBytes, Cohort.class);
        validateCohort(cohort);

        EvaluationMemoryRepository repository = new EvaluationMemoryRepository(
                memories(cohort.memories()));
        PineconeSession pinecone = connectPinecone(mapper);
        PineconeAutoMemoryVectorStoreAdapter vectors = pinecone.vectors();
        List<AutoMemoryVectorDocument> documents = repository.all().stream()
                .map(memory -> AutoMemoryVectorDocument.current(memory, memory.version()))
                .toList();
        List<String> vectorIds = documents.stream()
                .map(AutoMemoryVectorDocument::vectorId)
                .toList();

        try {
            List<float[]> embeddings = vectors.embedPassages(documents.stream()
                    .map(AutoMemoryVectorDocument::retrievalText)
                    .toList());
            List<AutoMemoryVector> projections = new ArrayList<>(documents.size());
            for (int index = 0; index < documents.size(); index++) {
                projections.add(new AutoMemoryVector(documents.get(index), embeddings.get(index)));
            }
            vectors.upsert(projections);
            waitUntilVisible(vectors, vectorIds);
            waitUntilQueryable(vectors, repository.all().get(0));

            AutoMemoryContextSelector.Budget budget =
                    new AutoMemoryContextSelector.Budget(12, 6_000);
            Evaluation sql = evaluate(
                    new AutoMemoryContextSelector(repository, repository, budget),
                    cohort.cases(),
                    repository);
            Evaluation semantic = evaluate(
                    new AutoMemoryContextSelector(repository, repository, vectors, budget),
                    cohort.cases(),
                    repository);
            EvaluationReport result = compare(cohort, sql, semantic);
            Path report = reportPath();
            writeReport(mapper, report, cohort, pinecone, result);

            assertTrue(result.passed(), () -> "V1.7 context evaluation failed: sql="
                    + sql.metrics() + ", semantic=" + semantic.metrics()
                    + "; report=" + report.toAbsolutePath());
        } finally {
            vectors.delete(vectorIds);
            waitUntilDeleted(vectors, vectorIds);
        }
    }

    private Evaluation evaluate(
            AutoMemoryContextSelector selector,
            List<CaseSpec> cases,
            EvaluationMemoryRepository repository
    ) {
        List<CaseResult> results = new ArrayList<>();
        for (CaseSpec testCase : cases) {
            AutoMemoryContextQuery query = query(testCase);
            AutoMemoryContextSelection selection = selector.select(query);
            List<String> selected = selection.references().stream()
                    .map(AutoMemoryContextSelection.Reference::memoryId)
                    .toList();
            results.add(new CaseResult(
                    testCase.id(),
                    testCase.expectedRelevantIds(),
                    testCase.forbiddenSelectedIds(),
                    selected,
                    firstRelevantRank(selected, testCase.expectedRelevantIds()),
                    unauthorizedCount(query, selected, repository)));
        }
        return new Evaluation(metrics(results), List.copyOf(results));
    }

    private int unauthorizedCount(
            AutoMemoryContextQuery query,
            List<String> selected,
            EvaluationMemoryRepository repository
    ) {
        // Recheck the selector output against the fixture authority, independently of hydration.
        Set<AutoMemoryScope> authorized = Set.copyOf(query.authorizedScopes());
        return (int) selected.stream()
                .map(repository::find)
                .flatMap(Optional::stream)
                .filter(memory -> !authorized.contains(memory.scope()))
                .count();
    }

    private Metrics metrics(List<CaseResult> cases) {
        int expected = 0;
        int hitAt1 = 0;
        int hitAt3 = 0;
        int hitAt12 = 0;
        int relevantAt3 = 0;
        int selectedAt3 = 0;
        int selected = 0;
        int selectedRelevant = 0;
        int forbidden = 0;
        int forbiddenOpportunities = 0;
        int unauthorized = 0;
        double reciprocalRanks = 0.0d;
        for (CaseResult result : cases) {
            expected += result.expectedRelevantIds().size();
            hitAt1 += hits(result, 1);
            hitAt3 += hits(result, 3);
            hitAt12 += hits(result, 12);
            relevantAt3 += hits(result, 3);
            selectedAt3 += Math.min(3, result.selectedIds().size());
            selected += result.selectedIds().size();
            selectedRelevant += hits(result, result.selectedIds().size());
            forbidden += result.forbiddenHits();
            forbiddenOpportunities += result.forbiddenSelectedIds().size();
            unauthorized += result.unauthorizedSelectionCount();
            if (result.firstRelevantRank() >= 0) {
                reciprocalRanks += 1.0d / (result.firstRelevantRank() + 1);
            }
        }
        return new Metrics(
                ratio(hitAt1, expected),
                ratio(hitAt3, expected),
                ratio(hitAt12, expected),
                ratio(reciprocalRanks, cases.size()),
                ratio(relevantAt3, selectedAt3),
                ratio(selected - selectedRelevant, selected),
                ratio(selected, cases.size()),
                ratio(forbidden, forbiddenOpportunities),
                unauthorized);
    }

    private int hits(CaseResult result, int limit) {
        Set<String> expected = Set.copyOf(result.expectedRelevantIds());
        return (int) result.selectedIds().stream()
                .limit(limit)
                .filter(expected::contains)
                .count();
    }

    private EvaluationReport compare(Cohort cohort, Evaluation sql, Evaluation semantic) {
        QualityGate gate = cohort.qualityGate();
        double recallAt3Lift = semantic.metrics().recallAt3() - sql.metrics().recallAt3();
        boolean passed = semantic.metrics().recallAt1() >= gate.minimumSemanticRecallAt1()
                && semantic.metrics().recallAt3() >= gate.minimumSemanticRecallAt3()
                && semantic.metrics().recallAt12() >= gate.minimumSemanticRecallAt12()
                && semantic.metrics().mrr() >= gate.minimumSemanticMrr()
                && recallAt3Lift >= gate.minimumRecallAt3LiftOverSql()
                && semantic.metrics().forbiddenSelectionRate()
                <= gate.maximumForbiddenSelectionRate()
                && semantic.metrics().unauthorizedSelectionCount()
                <= gate.maximumUnauthorizedSelectionCount();
        List<Comparison> comparisons = new ArrayList<>();
        for (int index = 0; index < cohort.cases().size(); index++) {
            CaseSpec testCase = cohort.cases().get(index);
            CaseResult sqlCase = sql.cases().get(index);
            CaseResult semanticCase = semantic.cases().get(index);
            comparisons.add(new Comparison(
                    testCase.id(),
                    testCase.queryText(),
                    testCase.expectedRelevantIds(),
                    testCase.forbiddenSelectedIds(),
                    sqlCase.selectedIds(),
                    semanticCase.selectedIds(),
                    sqlCase.firstRelevantRank() < 0 ? null : sqlCase.firstRelevantRank() + 1,
                    semanticCase.firstRelevantRank() < 0
                            ? null : semanticCase.firstRelevantRank() + 1));
        }
        return new EvaluationReport(
                sql.metrics(), semantic.metrics(), recallAt3Lift,
                List.copyOf(comparisons), passed);
    }

    private List<AutoMemory> memories(List<MemorySpec> specifications) {
        List<AutoMemory> result = new ArrayList<>(specifications.size());
        for (int index = 0; index < specifications.size(); index++) {
            MemorySpec spec = specifications.get(index);
            AutoMemoryScope scope = switch (spec.scope()) {
                case "USER" -> AutoMemoryScope.user(OWNER);
                case "CHARTBOOK" -> AutoMemoryScope.chartbook(OWNER, CHARTBOOK);
                default -> throw new IllegalArgumentException("unknown evaluation scope");
            };
            Instant updatedAt = switch (spec.baselinePriority()) {
                case "OLD" -> OLD.minusSeconds(index);
                case "RECENT" -> RECENT.plusSeconds(index);
                default -> throw new IllegalArgumentException("unknown baseline priority");
            };
            result.add(new AutoMemory(
                    spec.id(), scope, AutoMemoryType.PREFERENCE, spec.semanticKey(),
                    spec.title(), spec.canonicalText(), AutoMemoryStatus.ACTIVE,
                    1.0d, 1, true, 1L, updatedAt, updatedAt));
        }
        return List.copyOf(result);
    }

    private AutoMemoryContextQuery query(CaseSpec testCase) {
        return new AutoMemoryContextQuery(
                new TurnKey(OWNER, "memory-context-eval", testCase.id()),
                testCase.chartbookContext() ? CHARTBOOK : null,
                testCase.queryText());
    }

    private int firstRelevantRank(List<String> selected, List<String> expected) {
        Set<String> relevant = Set.copyOf(expected);
        for (int index = 0; index < selected.size(); index++) {
            if (relevant.contains(selected.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private void validateCohort(Cohort cohort) {
        assertEquals("AUTO_MEMORY_CONTEXT_HOLDOUT_V1", cohort.schemaVersion());
        assertEquals("synthetic", cohort.privacyClassification());
        assertEquals("frozen-holdout-not-for-tuning", cohort.usagePolicy());
        assertTrue(cohort.cases().size() >= 10, "holdout needs enough independent cases");
        assertTrue(cohort.memories().size() > 32, "holdout needs more than the SQL window");
        Set<String> ids = new LinkedHashSet<>();
        cohort.memories().forEach(memory -> assertTrue(ids.add(memory.id()),
                "holdout Memory IDs must be unique"));
        cohort.cases().forEach(testCase -> {
            assertTrue(!testCase.expectedRelevantIds().isEmpty(),
                    "every holdout case needs a relevance label");
            assertTrue(ids.containsAll(testCase.expectedRelevantIds()),
                    "relevance labels must reference the frozen Memory bank");
            assertTrue(ids.containsAll(testCase.forbiddenSelectedIds()),
                    "forbidden labels must reference the frozen Memory bank");
        });
    }

    private PineconeSession connectPinecone(ObjectMapper mapper) {
        String namespace = requiredEnvironment("AUTO_MEMORY_VECTOR_PINECONE_NAMESPACE");
        assertTrue(namespace.toLowerCase(Locale.ROOT).contains("eval"),
                "V1.7 evaluation requires an isolated eval namespace");
        String model = environment(
                "AUTO_MEMORY_VECTOR_EMBEDDING_MODEL",
                environment("PINECONE_EMBEDDING_MODEL", "multilingual-e5-large"));
        int dimension = Integer.parseInt(environment(
                "AUTO_MEMORY_VECTOR_DIMENSION",
                environment("PINECONE_DIMENSION", "1024")));
        PineconeVectorClient client = new PineconeVectorClient(
                requiredEnvironment("AUTO_MEMORY_VECTOR_PINECONE_API_KEY", "PINECONE_API_KEY"),
                requiredEnvironment(
                        "AUTO_MEMORY_VECTOR_PINECONE_INDEX_HOST", "PINECONE_INDEX_HOST"),
                model,
                dimension,
                mapper,
                PineconeAutoMemoryVectorStoreAdapter.METADATA_FIELDS);
        return new PineconeSession(
                new PineconeAutoMemoryVectorStoreAdapter(
                        client,
                        namespace,
                        requiredEnvironment(
                                "AUTO_MEMORY_VECTOR_PARTITION_SECRET",
                                "PINECONE_TENANT_HMAC_SECRET")),
                namespace,
                model,
                dimension);
    }

    private void waitUntilVisible(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            List<String> vectorIds
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (vectors.existingVectorIds(vectorIds).containsAll(vectorIds)) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("V1.7 evaluation vectors were not visible");
    }

    private void waitUntilQueryable(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            AutoMemory probe
    ) throws InterruptedException {
        String vectorId = AutoMemoryVectorDocument.current(probe, probe.version()).vectorId();
        for (int attempt = 0; attempt < 20; attempt++) {
            AutoMemoryVectorSearchQuery query = AutoMemoryVectorSearchQuery.activeContext(
                    new TurnKey(OWNER, "memory-context-eval", "index-probe"),
                    null,
                    probe.canonicalText());
            if (vectors.search(query, 32).contains(vectorId)) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("V1.7 evaluation namespace was not queryable");
    }

    private void waitUntilDeleted(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            List<String> vectorIds
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (vectors.existingVectorIds(vectorIds).isEmpty()) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("V1.7 evaluation vectors remained after cleanup");
    }

    private void writeReport(
            ObjectMapper mapper,
            Path path,
            Cohort cohort,
            PineconeSession pinecone,
            EvaluationReport result
    ) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "AUTO_MEMORY_CONTEXT_EVALUATION_REPORT_V1");
        report.put("generatedAt", Instant.now().toString());
        report.put("datasetVersion", cohort.datasetVersion());
        report.put("datasetSha256", HOLDOUT_SHA256);
        report.put("privacyClassification", cohort.privacyClassification());
        report.put("usagePolicy", cohort.usagePolicy());
        report.put("businessDatabaseWrites", 0);
        report.put("deepSeekCalls", 0);
        report.put("namespaceClass", "isolated-eval");
        report.put("embeddingModel", pinecone.model());
        report.put("embeddingDimension", pinecone.dimension());
        report.put("caseCount", cohort.cases().size());
        report.put("memoryCount", cohort.memories().size());
        report.put("qualityGate", cohort.qualityGate());
        report.put("sqlBaseline", result.sql());
        report.put("semantic", result.semantic());
        report.put("semanticRecallAt3LiftOverSql", result.recallAt3Lift());
        report.put("passed", result.passed());
        report.put("cases", result.cases());
        Files.createDirectories(path.toAbsolutePath().getParent());
        mapper.writeValue(path.toFile(), report);
    }

    private Path holdoutPath() {
        String relative = "ai-agent-draw-io-infrastructure/src/test/resources/evals/"
                + "auto-memory-context-v1/holdout.json";
        for (Path candidate : List.of(Path.of(relative), Path.of("..").resolve(relative))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("V1.7 context holdout was not found");
    }

    private Path reportPath() {
        String configured = System.getenv("AUTO_MEMORY_CONTEXT_EVALUATION_REPORT");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of("target", "auto-memory-retrieval", "context-v1-report.json");
    }

    private String requiredEnvironment(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalStateException("required V1.7 evaluation environment is missing");
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static double ratio(double numerator, double denominator) {
        return denominator == 0.0d ? 0.0d : numerator / denominator;
    }

    private record Cohort(
            String schemaVersion,
            String datasetVersion,
            String privacyClassification,
            String usagePolicy,
            QualityGate qualityGate,
            List<MemorySpec> memories,
            List<CaseSpec> cases
    ) {
    }

    private record QualityGate(
            double minimumSemanticRecallAt1,
            double minimumSemanticRecallAt3,
            double minimumSemanticRecallAt12,
            double minimumSemanticMrr,
            double minimumRecallAt3LiftOverSql,
            double maximumForbiddenSelectionRate,
            int maximumUnauthorizedSelectionCount
    ) {
    }

    private record MemorySpec(
            String id,
            String scope,
            String semanticKey,
            String title,
            String canonicalText,
            String baselinePriority
    ) {
    }

    private record CaseSpec(
            String id,
            String queryText,
            boolean chartbookContext,
            List<String> expectedRelevantIds,
            List<String> forbiddenSelectedIds
    ) {
    }

    private record CaseResult(
            String id,
            List<String> expectedRelevantIds,
            List<String> forbiddenSelectedIds,
            List<String> selectedIds,
            int firstRelevantRank,
            int unauthorizedSelectionCount
    ) {
        private int forbiddenHits() {
            Set<String> forbidden = Set.copyOf(forbiddenSelectedIds);
            return (int) selectedIds.stream().filter(forbidden::contains).count();
        }
    }

    private record Metrics(
            double recallAt1,
            double recallAt3,
            double recallAt12,
            double mrr,
            double precisionAt3,
            double irrelevantSelectionRate,
            double meanSelectedCount,
            double forbiddenSelectionRate,
            int unauthorizedSelectionCount
    ) {
    }

    private record Evaluation(Metrics metrics, List<CaseResult> cases) {
    }

    private record Comparison(
            String id,
            String queryText,
            List<String> expectedRelevantIds,
            List<String> forbiddenSelectedIds,
            List<String> sqlSelectedIds,
            List<String> semanticSelectedIds,
            Integer sqlFirstRelevantRank,
            Integer semanticFirstRelevantRank
    ) {
    }

    private record EvaluationReport(
            Metrics sql,
            Metrics semantic,
            double recallAt3Lift,
            List<Comparison> cases,
            boolean passed
    ) {
    }

    private record PineconeSession(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            String namespace,
            String model,
            int dimension
    ) {
    }

    /** Read-only authority used only by the synthetic holdout. */
    private static final class EvaluationMemoryRepository
            implements AutoMemoryQueryPort, AutoMemoryContextHydrationPort {
        private final List<AutoMemory> memories;
        private final Map<String, AutoMemory> byId;

        private EvaluationMemoryRepository(List<AutoMemory> memories) {
            this.memories = List.copyOf(memories);
            this.byId = new HashMap<>();
            memories.forEach(memory -> this.byId.put(memory.memoryId(), memory));
        }

        private List<AutoMemory> all() {
            return memories;
        }

        private Optional<AutoMemory> find(String memoryId) {
            return Optional.ofNullable(byId.get(memoryId));
        }

        @Override
        public List<AutoMemory> recallActive(AutoMemoryScope scope, int limit) {
            Comparator<AutoMemory> order = Comparator
                    .comparing(AutoMemory::explicit).reversed()
                    .thenComparing(Comparator.comparingDouble(AutoMemory::confidence).reversed())
                    .thenComparing(AutoMemory::updatedAt, Comparator.reverseOrder())
                    .thenComparing(AutoMemory::memoryId);
            return memories.stream()
                    .filter(memory -> memory.status() == AutoMemoryStatus.ACTIVE)
                    .filter(memory -> memory.scope().equals(scope))
                    .sorted(order)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<AutoMemory> findConsolidationCandidates(AutoMemoryScope scope, int limit) {
            return List.of();
        }

        @Override
        public List<AutoMemory> hydrateActiveVectorMatches(
                AutoMemoryContextQuery query,
                List<String> rankedVectorIds
        ) {
            Set<AutoMemoryScope> authorized = Set.copyOf(query.authorizedScopes());
            Set<String> selected = new LinkedHashSet<>();
            List<AutoMemory> result = new ArrayList<>();
            for (String vectorId : rankedVectorIds) {
                AutoMemoryVectorDocument.currentMemoryIdFromVectorId(vectorId).ifPresent(id -> {
                    AutoMemory memory = byId.get(id);
                    if (memory != null
                            && memory.status() == AutoMemoryStatus.ACTIVE
                            && authorized.contains(memory.scope())
                            && selected.add(id)
                            && AutoMemoryVectorDocument.current(memory, memory.version())
                            .vectorId().equals(vectorId)) {
                        result.add(memory);
                    }
                });
            }
            return List.copyOf(result);
        }

        @Override
        public List<AutoMemory> loadActive(
                AutoMemoryContextQuery query,
                List<String> memoryIds
        ) {
            Set<AutoMemoryScope> authorized = Set.copyOf(query.authorizedScopes());
            return memoryIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .filter(memory -> memory.status() == AutoMemoryStatus.ACTIVE)
                    .filter(memory -> authorized.contains(memory.scope()))
                    .toList();
        }
    }
}
