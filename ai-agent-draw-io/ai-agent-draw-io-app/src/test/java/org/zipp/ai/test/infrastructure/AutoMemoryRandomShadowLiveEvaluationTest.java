package org.zipp.ai.test.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.AutoMemoryConsolidationQuery;
import org.zipp.ai.application.memory.AutoMemoryExtractionCandidate;
import org.zipp.ai.application.memory.AutoMemoryObservationCommand;
import org.zipp.ai.application.memory.AutoMemoryObservationOutcome;
import org.zipp.ai.application.memory.AutoMemoryObservationService;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.AutoMemoryVector;
import org.zipp.ai.application.memory.AutoMemoryVectorCandidateHydrationPort;
import org.zipp.ai.application.memory.AutoMemoryVectorDocument;
import org.zipp.ai.application.memory.AutoMemoryVectorShadowTelemetry;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchQuery;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchHit;
import org.zipp.ai.application.memory.AutoMemoryVectorStorePort;
import org.zipp.ai.application.memory.MemoryObservationKind;
import org.zipp.ai.application.memory.MemoryScopeType;
import org.zipp.ai.application.memory.ScopedAutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.memory.ShadowAutoMemoryConsolidationCandidateRetriever;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.infrastructure.adapter.repository.MySqlAutoMemoryAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeAutoMemoryVectorStoreAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in, seeded local simulation of the SQL-preserving production shadow path. */
@EnabledIfEnvironmentVariable(
        named = "AUTO_MEMORY_RANDOM_SHADOW_EVALUATION_ENABLED",
        matches = "true")
class AutoMemoryRandomShadowLiveEvaluationTest {
    private static final int LIMIT_PER_SCOPE = 8;
    private static final int DEFAULT_TURN_COUNT = 32;
    private static final long DEFAULT_SEED = 20260802L;
    private static final Instant OLD = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void seededTurnsCompareVectorRecallWithTheAuthoritativeSqlWindow() throws Exception {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Scenario scenario = scenario(mapper);
        long seed = longEnvironment("AUTO_MEMORY_RANDOM_SHADOW_SEED", DEFAULT_SEED);
        int turnCount = intEnvironment(
                "AUTO_MEMORY_RANDOM_SHADOW_TURNS",
                DEFAULT_TURN_COUNT,
                scenario.targets().size(),
                200);
        DriverManagerDataSource dataSource = localDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        requireSchema(jdbc);

        PineconeSession pinecone = connectPinecone(mapper);
        PineconeAutoMemoryVectorStoreAdapter vectors = pinecone.vectors();

        String runId = "amshadow_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);
        Fixture fixture = null;
        List<String> vectorIds = List.of();
        try {
            fixture = createFixture(jdbc, dataSource, runId, seed, scenario);
            List<AutoMemoryVectorDocument> documents = fixture.memories().stream()
                    .map(memory -> AutoMemoryVectorDocument.current(memory, memory.version()))
                    .toList();
            List<float[]> embeddings = vectors.embedPassages(documents.stream()
                    .map(AutoMemoryVectorDocument::retrievalText)
                    .toList());
            List<AutoMemoryVector> projections = new ArrayList<>(documents.size());
            Map<String, AutoMemoryScope> scopesByVectorId = new LinkedHashMap<>();
            for (int index = 0; index < documents.size(); index++) {
                AutoMemoryVectorDocument document = documents.get(index);
                projections.add(new AutoMemoryVector(document, embeddings.get(index)));
                scopesByVectorId.put(document.vectorId(), document.scope());
            }
            vectorIds = documents.stream().map(AutoMemoryVectorDocument::vectorId).toList();
            vectors.upsert(projections);
            waitUntilVisible(vectors, vectorIds);
            waitUntilTargetsIndexed(vectors, fixture);

            Simulation result = simulate(
                    fixture, vectors, scopesByVectorId, seed, turnCount);
            Path report = reportPath();
            writeReport(
                    mapper,
                    report,
                    pinecone.namespace(),
                    pinecone.model(),
                    pinecone.dimension(),
                    seed,
                    fixture,
                    result);

            assertTrue(result.passed(), () -> "Random Memory shadow simulation failed: "
                    + result.metrics() + "; report=" + report.toAbsolutePath());
        } finally {
            try {
                if (!vectorIds.isEmpty()) {
                    vectors.delete(vectorIds);
                    waitUntilDeleted(vectors, vectorIds);
                }
            } finally {
                // MySQL cleanup must still happen if the remote cleanup check fails.
                cleanup(jdbc, fixture, runId);
            }
        }
    }

    private Fixture createFixture(
            JdbcTemplate jdbc,
            DriverManagerDataSource dataSource,
            String runId,
            long seed,
            Scenario scenario
    ) {
        String owner = runId + "_owner";
        String otherOwner = runId + "_other";
        String chartbook = runId + "_book";
        String otherChartbook = runId + "_other_book";
        jdbc.update("INSERT INTO chartbook (id, owner_key, name, status) "
                        + "VALUES (?, ?, 'Random Memory shadow', 'ACTIVE')",
                chartbook, owner);
        jdbc.update("INSERT INTO chartbook (id, owner_key, name, status) "
                        + "VALUES (?, ?, 'Random Memory shadow distractor', 'ACTIVE')",
                otherChartbook, owner);

        MySqlAutoMemoryAdapter adapter = new MySqlAutoMemoryAdapter(jdbc);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        AutoMemoryObservationService observations = new AutoMemoryObservationService(adapter);
        Random random = new Random(seed);
        List<MemoryScopeType> assignments = new ArrayList<>();
        for (int index = 0; index < scenario.targets().size(); index++) {
            assignments.add(index < scenario.targets().size() / 2
                    ? MemoryScopeType.USER : MemoryScopeType.CHARTBOOK);
        }
        Collections.shuffle(assignments, random);

        List<AutoMemory> memories = new ArrayList<>();
        Map<String, Target> targets = new LinkedHashMap<>();
        for (int index = 0; index < scenario.targets().size(); index++) {
            Topic topic = scenario.targets().get(index);
            AutoMemoryScope scope = assignments.get(index) == MemoryScopeType.USER
                    ? AutoMemoryScope.user(owner)
                    : AutoMemoryScope.chartbook(owner, chartbook);
            AutoMemory memory = observe(
                    observations, transactions, scope, topic, runId + "_target_" + index);
            setUpdatedAt(jdbc, memory.memoryId(), OLD.minus(Duration.ofDays(index)));
            memories.add(memory);
            targets.put(topic.semanticKey(), new Target(topic, memory));

            // Identical text outside the authorized scope makes tenant/scope filtering observable.
            memories.add(observe(
                    observations,
                    transactions,
                    AutoMemoryScope.chartbook(owner, otherChartbook),
                    topic,
                    runId + "_other_book_" + index));
            memories.add(observe(
                    observations,
                    transactions,
                    AutoMemoryScope.user(otherOwner),
                    topic,
                    runId + "_other_owner_" + index));
        }
        for (AutoMemoryScope scope : List.of(
                AutoMemoryScope.user(owner),
                AutoMemoryScope.chartbook(owner, chartbook))) {
            for (int index = 0; index < scenario.noise().size(); index++) {
                Topic source = scenario.noise().get(index);
                Topic noise = new Topic(
                        "noise-" + index,
                        source.title(),
                        source.canonicalText(),
                        source.queryText());
                AutoMemory memory = observe(
                        observations,
                        transactions,
                        scope,
                        noise,
                        runId + "_noise_" + scope.type().name().toLowerCase(Locale.ROOT)
                                + "_" + index);
                setUpdatedAt(jdbc, memory.memoryId(), RECENT.plus(Duration.ofMinutes(index)));
                memories.add(memory);
            }
        }
        return new Fixture(
                owner,
                otherOwner,
                chartbook,
                List.copyOf(memories),
                Map.copyOf(targets),
                adapter);
    }

    private AutoMemory observe(
            AutoMemoryObservationService observations,
            TransactionTemplate transactions,
            AutoMemoryScope scope,
            Topic topic,
            String identity
    ) {
        AutoMemoryObservationOutcome outcome = transactions.execute(status -> observations.observe(
                new AutoMemoryObservationCommand(
                        scope,
                        AutoMemoryType.PREFERENCE,
                        topic.semanticKey(),
                        topic.title(),
                        topic.canonicalText(),
                        new TurnKey(scope.ownerKey(), "random-shadow", identity),
                        null,
                        MemoryObservationKind.EXPLICIT,
                        1.0d)));
        if (outcome instanceof AutoMemoryObservationOutcome.Applied applied) {
            return applied.memory();
        }
        throw new IllegalStateException("failed to create synthetic Memory: " + outcome);
    }

    private Simulation simulate(
            Fixture fixture,
            PineconeAutoMemoryVectorStoreAdapter vectors,
            Map<String, AutoMemoryScope> scopesByVectorId,
            long seed,
            int turnCount
    ) {
        Random random = new Random(seed ^ 0x5DEECE66DL);
        List<TurnSpec> turns = randomTurns(
                random,
                turnCount,
                fixture.targets().values().stream().map(Target::topic).toList());
        RecordingVectorStore recordingVectors = new RecordingVectorStore(vectors);
        RecordingHydration recordingHydration = new RecordingHydration(fixture.adapter());
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AutoMemoryConsolidationCandidateRetriever sql =
                new ScopedAutoMemoryConsolidationCandidateRetriever(fixture.adapter());
        AutoMemoryConsolidationCandidateRetriever shadow =
                new ShadowAutoMemoryConsolidationCandidateRetriever(
                        sql, recordingVectors, recordingHydration, telemetry);

        int sqlRelevant = 0;
        int vectorRelevant = 0;
        int vectorTop1 = 0;
        int preserved = 0;
        int unauthorizedRawHits = 0;
        int unknownRawHits = 0;
        int totalSql = 0;
        int totalVectorHits = 0;
        int totalHydrated = 0;
        int totalOverlap = 0;
        List<Map<String, Object>> reports = new ArrayList<>();
        for (int index = 0; index < turns.size(); index++) {
            TurnSpec turn = turns.get(index);
            Target target = fixture.targets().get(turn.semanticKey());
            AutoMemoryConsolidationQuery query = new AutoMemoryConsolidationQuery(
                    new TurnKey(fixture.owner(), "random-shadow", "turn-" + index),
                    fixture.chartbook(),
                    turn.queryText(),
                    LIMIT_PER_SCOPE);
            recordingVectors.reset();
            recordingHydration.reset();
            int samplesBefore = telemetry.samples().size();
            List<AutoMemoryExtractionCandidate> sqlResult = sql.retrieve(query);
            List<AutoMemoryExtractionCandidate> returned = shadow.retrieve(query);
            AutoMemoryVectorShadowTelemetry.Sample sample = telemetry.samples().get(samplesBefore);
            List<AutoMemoryExtractionCandidate> hydrated = recordingHydration.last();

            boolean sqlHit = contains(sqlResult, turn.semanticKey());
            int vectorRank = rank(hydrated, turn.semanticKey());
            boolean vectorHit = vectorRank >= 0;
            sqlRelevant += sqlHit ? 1 : 0;
            vectorRelevant += vectorHit ? 1 : 0;
            vectorTop1 += vectorRank == 0 ? 1 : 0;
            preserved += returned.equals(sqlResult) ? 1 : 0;
            totalSql += sample.sqlCandidateCount();
            totalVectorHits += sample.vectorHitCount();
            totalHydrated += sample.hydratedCandidateCount();
            totalOverlap += sample.overlapCount();

            Set<AutoMemoryScope> authorized = Set.copyOf(query.authorizedScopes());
            for (String vectorId : recordingVectors.last()) {
                AutoMemoryScope scope = scopesByVectorId.get(vectorId);
                if (scope == null) {
                    unknownRawHits++;
                } else if (!authorized.contains(scope)) {
                    unauthorizedRawHits++;
                }
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("turn", index + 1);
            report.put("queryText", turn.queryText());
            report.put("expectedSemanticKey", turn.semanticKey());
            report.put("expectedScope", target.memory().scope().type().name());
            report.put("sqlRelevant", sqlHit);
            report.put("vectorRelevantRank", vectorHit ? vectorRank + 1 : null);
            report.put("sqlCandidateCount", sample.sqlCandidateCount());
            report.put("vectorHitCount", sample.vectorHitCount());
            report.put("hydratedCandidateCount", sample.hydratedCandidateCount());
            report.put("overlapCount", sample.overlapCount());
            reports.add(Collections.unmodifiableMap(new LinkedHashMap<>(report)));
        }

        Metrics metrics = new Metrics(
                ratio(sqlRelevant, turnCount),
                ratio(vectorRelevant, turnCount),
                ratio(vectorTop1, turnCount),
                ratio(preserved, turnCount),
                ratio(telemetry.successCount(), turnCount),
                ratio(totalHydrated, totalVectorHits),
                ratio(totalOverlap, totalHydrated),
                ratio(totalSql, turnCount),
                ratio(totalVectorHits, turnCount),
                ratio(totalHydrated, turnCount),
                unauthorizedRawHits,
                unknownRawHits);
        // Avoid a model-specific threshold, but require semantic retrieval to beat the SQL window.
        boolean passed = metrics.shadowSuccessRate() == 1.0d
                && metrics.shadowReturnPreservationRate() == 1.0d
                && metrics.rawUnauthorizedVectorHitCount() == 0
                && metrics.unknownRawVectorHitCount() == 0
                && metrics.vectorRelevantRecallAtK() > metrics.sqlRelevantRecallAtK();
        return new Simulation(metrics, List.copyOf(reports), passed);
    }

    private List<TurnSpec> randomTurns(
            Random random,
            int turnCount,
            List<Topic> topics
    ) {
        List<TurnSpec> turns = new ArrayList<>();
        for (Topic topic : topics) {
            turns.add(new TurnSpec(topic.semanticKey(), randomQuery(topic, random)));
        }
        while (turns.size() < turnCount) {
            Topic topic = topics.get(random.nextInt(topics.size()));
            turns.add(new TurnSpec(topic.semanticKey(), randomQuery(topic, random)));
        }
        Collections.shuffle(turns, random);
        return List.copyOf(turns);
    }

    private String randomQuery(Topic topic, Random random) {
        String query = topic.queryText();
        return switch (random.nextInt(4)) {
            case 0 -> query;
            case 1 -> "For the next diagram, " + lowerInitial(query);
            case 2 -> "One stable preference: " + query;
            default -> "绘图时请注意：" + query;
        };
    }

    private void waitUntilTargetsIndexed(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            Fixture fixture
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            boolean allIndexed = true;
            for (Target target : fixture.targets().values()) {
                AutoMemory memory = target.memory();
                AutoMemoryConsolidationQuery probe = new AutoMemoryConsolidationQuery(
                        new TurnKey(fixture.owner(), "random-shadow-index", memory.memoryId()),
                        fixture.chartbook(),
                        target.topic().canonicalText(),
                        16);
                String vectorId = AutoMemoryVectorDocument.current(
                        memory, memory.version()).vectorId();
                if (!vectors.search(probe, 32).contains(vectorId)) {
                    allIndexed = false;
                    break;
                }
            }
            if (allIndexed) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Random shadow targets were not indexed within 10 seconds");
    }

    private void writeReport(
            ObjectMapper mapper,
            Path path,
            String namespace,
            String model,
            int dimension,
            long seed,
            Fixture fixture,
            Simulation result
    ) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "AUTO_MEMORY_RANDOM_SHADOW_REPORT_V1");
        report.put("generatedAt", Instant.now().toString());
        report.put("seed", seed);
        report.put("turnCount", result.turns().size());
        report.put("topicCount", fixture.targets().size());
        report.put("fixtureMemoryCount", fixture.memories().size());
        report.put("limitPerScope", LIMIT_PER_SCOPE);
        report.put("embeddingModel", model);
        report.put("dimension", dimension);
        String normalizedNamespace = namespace.toLowerCase(Locale.ROOT);
        report.put("namespaceClass", normalizedNamespace.contains("test")
                ? "test" : normalizedNamespace.contains("dev") ? "dev" : "eval");
        report.put("metrics", result.metrics());
        report.put("passed", result.passed());
        report.put("turns", result.turns());
        Files.createDirectories(path.toAbsolutePath().getParent());
        mapper.writeValue(path.toFile(), report);
    }

    private PineconeSession connectPinecone(ObjectMapper mapper) {
        String namespace = requiredEnvironment("AUTO_MEMORY_VECTOR_PINECONE_NAMESPACE");
        String normalized = namespace.toLowerCase(Locale.ROOT);
        assertTrue(normalized.contains("test")
                        || normalized.contains("dev")
                        || normalized.contains("eval"),
                "random shadow evaluation requires a disposable namespace");
        String model = environment(
                "AUTO_MEMORY_VECTOR_EMBEDDING_MODEL",
                environment("PINECONE_EMBEDDING_MODEL", "multilingual-e5-large"));
        int dimension = intEnvironment(
                "AUTO_MEMORY_VECTOR_DIMENSION",
                Integer.parseInt(environment("PINECONE_DIMENSION", "1024")),
                1,
                100_000);
        PineconeVectorClient client = new PineconeVectorClient(
                requiredEnvironment(
                        "AUTO_MEMORY_VECTOR_PINECONE_API_KEY", "PINECONE_API_KEY"),
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
        throw new IllegalStateException("Random shadow vectors were not visible within 10 seconds");
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
        throw new IllegalStateException("Random shadow vectors remained after cleanup");
    }

    private DriverManagerDataSource localDataSource() {
        String url = environment("AUTO_MEMORY_MYSQL_JDBC_URL", "");
        if (url.isBlank()) {
            String host = environment("MYSQL_HOST", "127.0.0.1");
            String port = environment("MYSQL_PORT", "3307");
            String database = environment("MYSQL_DATABASE", "ai_draw_io");
            url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        }
        String normalized = url.toLowerCase(Locale.ROOT);
        assertTrue(normalized.startsWith("jdbc:mysql://127.0.0.1:")
                        || normalized.startsWith("jdbc:mysql://localhost:"),
                "random shadow evaluation requires a local MySQL URL");
        return new DriverManagerDataSource(
                url,
                environment("AUTO_MEMORY_MYSQL_USER", environment("MYSQL_USER", "root")),
                environment(
                        "AUTO_MEMORY_MYSQL_PASSWORD",
                        environment("MYSQL_ROOT_PASSWORD", "")));
    }

    private void requireSchema(JdbcTemplate jdbc) {
        Integer tables = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('chartbook', 'memory_item', 'memory_evidence')
                """, Integer.class);
        assertEquals(3, tables, "local database must have the released Auto Memory schema");
    }

    private void setUpdatedAt(JdbcTemplate jdbc, String memoryId, Instant updatedAt) {
        jdbc.update(
                "UPDATE memory_item SET created_at = ?, updated_at = ? WHERE memory_id = ?",
                Timestamp.from(updatedAt), Timestamp.from(updatedAt), memoryId);
    }

    private void cleanup(JdbcTemplate jdbc, Fixture fixture, String runId) {
        String owner = fixture == null ? runId + "_owner" : fixture.owner();
        String otherOwner = fixture == null ? runId + "_other" : fixture.otherOwner();
        jdbc.update("DELETE FROM memory_item WHERE owner_key IN (?, ?)",
                owner, otherOwner);
        jdbc.update("DELETE FROM chartbook WHERE owner_key IN (?, ?)",
                owner, otherOwner);
    }

    private Path reportPath() {
        String configured = System.getenv("AUTO_MEMORY_RANDOM_SHADOW_REPORT");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of("target", "auto-memory-retrieval", "random-shadow.json");
    }

    private Scenario scenario(ObjectMapper mapper) throws Exception {
        JsonNode cases = mapper.readTree(Files.readString(cohortPath())).path("cases");
        List<Topic> targets = new ArrayList<>();
        List<Topic> noise = new ArrayList<>();
        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            JsonNode testCase = cases.get(caseIndex);
            String expectedId = testCase.path("expectedRelevantIds").get(0).asText();
            for (JsonNode candidate : testCase.path("candidates")) {
                Topic topic = new Topic(
                        "sim-" + caseIndex + "-" + candidate.path("semanticKey").asText(),
                        candidate.path("title").asText(),
                        candidate.path("canonicalText").asText(),
                        testCase.path("queryText").asText());
                if (candidate.path("id").asText().equals(expectedId)) {
                    targets.add(topic);
                } else {
                    noise.add(topic);
                }
            }
        }
        if (noise.size() <= LIMIT_PER_SCOPE) {
            throw new IllegalStateException("random shadow needs more noise than the SQL limit");
        }
        return new Scenario(List.copyOf(targets), List.copyOf(noise));
    }

    private Path cohortPath() {
        String relative = "ai-agent-draw-io-infrastructure/src/test/resources/evals/"
                + "auto-memory-retrieval-v2/cohort.json";
        for (Path candidate : List.of(Path.of(relative), Path.of("..").resolve(relative))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("Auto Memory retrieval cohort was not found");
    }

    private boolean contains(List<AutoMemoryExtractionCandidate> values, String semanticKey) {
        return values.stream().anyMatch(value -> value.semanticKey().equals(semanticKey));
    }

    private int rank(List<AutoMemoryExtractionCandidate> values, String semanticKey) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).semanticKey().equals(semanticKey)) {
                return index;
            }
        }
        return -1;
    }

    private String lowerInitial(String value) {
        return value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0d : (double) numerator / denominator;
    }

    private String requiredEnvironment(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalStateException("required random shadow environment is missing");
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int intEnvironment(String name, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(environment(name, Integer.toString(fallback)));
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between "
                    + minimum + " and " + maximum);
        }
        return value;
    }

    private long longEnvironment(String name, long fallback) {
        return Long.parseLong(environment(name, Long.toString(fallback)));
    }

    private record Topic(
            String semanticKey,
            String title,
            String canonicalText,
            String queryText
    ) {
    }

    private record Scenario(List<Topic> targets, List<Topic> noise) {
    }

    private record PineconeSession(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            String namespace,
            String model,
            int dimension
    ) {
    }

    private record Target(Topic topic, AutoMemory memory) {
    }

    private record Fixture(
            String owner,
            String otherOwner,
            String chartbook,
            List<AutoMemory> memories,
            Map<String, Target> targets,
            MySqlAutoMemoryAdapter adapter
    ) {
    }

    private record TurnSpec(String semanticKey, String queryText) {
    }

    private record Metrics(
            double sqlRelevantRecallAtK,
            double vectorRelevantRecallAtK,
            double vectorTop1Rate,
            double shadowReturnPreservationRate,
            double shadowSuccessRate,
            double vectorHydrationRate,
            double vectorToSqlOverlapRate,
            double meanSqlCandidateCount,
            double meanVectorHitCount,
            double meanHydratedCandidateCount,
            int rawUnauthorizedVectorHitCount,
            int unknownRawVectorHitCount
    ) {
    }

    private record Simulation(
            Metrics metrics,
            List<Map<String, Object>> turns,
            boolean passed
    ) {
    }

    private static final class RecordingVectorStore implements AutoMemoryVectorStorePort {
        private final AutoMemoryVectorStorePort delegate;
        private List<String> last = List.of();

        private RecordingVectorStore(AutoMemoryVectorStorePort delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<float[]> embedPassages(List<String> texts) {
            return delegate.embedPassages(texts);
        }

        @Override
        public void upsert(List<AutoMemoryVector> vectors) {
            delegate.upsert(vectors);
        }

        @Override
        public Set<String> existingVectorIds(List<String> vectorIds) {
            return delegate.existingVectorIds(vectorIds);
        }

        @Override
        public void delete(List<String> vectorIds) {
            delegate.delete(vectorIds);
        }

        @Override
        public List<AutoMemoryVectorSearchHit> search(
                AutoMemoryVectorSearchQuery query,
                int topK
        ) {
            List<AutoMemoryVectorSearchHit> hits = delegate.search(query, topK);
            last = hits.stream().map(AutoMemoryVectorSearchHit::vectorId).toList();
            return hits;
        }

        private void reset() {
            last = List.of();
        }

        private List<String> last() {
            return last;
        }
    }

    private static final class RecordingHydration implements AutoMemoryVectorCandidateHydrationPort {
        private final AutoMemoryVectorCandidateHydrationPort delegate;
        private List<AutoMemoryExtractionCandidate> last = List.of();

        private RecordingHydration(AutoMemoryVectorCandidateHydrationPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<AutoMemoryExtractionCandidate> hydrate(
                AutoMemoryConsolidationQuery query,
                List<String> rankedVectorIds
        ) {
            last = delegate.hydrate(query, rankedVectorIds);
            return last;
        }

        private void reset() {
            last = List.of();
        }

        private List<AutoMemoryExtractionCandidate> last() {
            return last;
        }
    }

    private static final class RecordingTelemetry implements AutoMemoryVectorShadowTelemetry {
        private final List<Sample> samples = new ArrayList<>();

        @Override
        public void record(Sample sample) {
            samples.add(sample);
        }

        private List<Sample> samples() {
            return samples;
        }

        private long successCount() {
            return samples.stream().filter(Sample::succeeded).count();
        }
    }
}
