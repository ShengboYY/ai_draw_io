package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryFence;
import org.zipp.ai.application.memory.AutoMemoryManagementOutcome;
import org.zipp.ai.application.memory.AutoMemoryObservationCommand;
import org.zipp.ai.application.memory.AutoMemoryObservationOutcome;
import org.zipp.ai.application.memory.AutoMemoryObservationService;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.AutoMemoryVector;
import org.zipp.ai.application.memory.AutoMemoryVectorDocument;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchHit;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchPort;
import org.zipp.ai.application.memory.AutoMemoryVectorSearchQuery;
import org.zipp.ai.application.memory.MemoryObservationKind;
import org.zipp.ai.application.memory.MemoryScopeType;
import org.zipp.ai.application.turn.AttemptLease;
import org.zipp.ai.application.turn.ExecutionPolicySnapshot;
import org.zipp.ai.application.turn.FencedAttempt;
import org.zipp.ai.application.turn.ModelInputBinding;
import org.zipp.ai.application.turn.PlainDrawAction;
import org.zipp.ai.application.turn.PlainDrawPlan;
import org.zipp.ai.application.turn.PlainExecutionProfile;
import org.zipp.ai.application.turn.PlainGenerationRequest;
import org.zipp.ai.application.turn.TurnEngineMode;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.application.turn.context.AbsentContext;
import org.zipp.ai.application.turn.context.AutoMemoryContext;
import org.zipp.ai.application.turn.context.AutoMemoryContextQuery;
import org.zipp.ai.application.turn.context.AutoMemoryContextSelection;
import org.zipp.ai.application.turn.context.AutoMemoryContextSelector;
import org.zipp.ai.application.turn.context.AutoMemoryRecallPlanner;
import org.zipp.ai.application.turn.context.AutoMemoryRecallPlanningEligibilityPolicy;
import org.zipp.ai.application.turn.context.AvailableContext;
import org.zipp.ai.application.turn.context.BaseTurnContext;
import org.zipp.ai.application.turn.context.ContextDiagnostics;
import org.zipp.ai.application.turn.context.ContextReadSet;
import org.zipp.ai.application.turn.context.ContextSlice;
import org.zipp.ai.application.turn.context.ContextSlicePin;
import org.zipp.ai.application.turn.context.CurrentRequestContext;
import org.zipp.ai.application.turn.demand.CurrentInstruction;
import org.zipp.ai.infrastructure.adapter.repository.MySqlAutoMemoryAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeAutoMemoryVectorStoreAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in synthetic evaluation of the production Planner-to-Prompt Memory path. */
@EnabledIfEnvironmentVariable(
        named = "AUTO_MEMORY_CONTEXT_E2E_EVALUATION_ENABLED",
        matches = "true")
class AutoMemoryContextEndToEndLiveEvaluationTest {
    private static final String DEVELOPMENT_SHA256 =
            "7fd96c2abd033cb094857705e7eb26dde9e7406bababe167f1b6e63476065f57";
    private static final String HOLDOUT_SHA256 =
            "5cc47ed2a1331a51ccacb5e055e622f3e187544b39beb168661395a053271a8f";
    private static final int MAX_TOKENS = 2_048;
    private static final Instant OLD = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant RECENT = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void plannerVectorAuthorityAndPromptMeetTheFrozenGate() throws Exception {
        Profile profile = profile();
        byte[] bytes = Files.readAllBytes(cohortPath(profile));
        assertEquals(profile.sha256(), sha256(bytes));
        Cohort cohort = cohort(JSON.parseObject(new String(bytes, StandardCharsets.UTF_8)));
        String runId = "amctx_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);
        DriverManagerDataSource dataSource = localDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        requireSchema(jdbc);

        Fixture fixture = null;
        VectorSession vectorSession = null;
        List<String> vectorIds = List.of();
        try {
            fixture = createFixture(jdbc, dataSource, runId, cohort);
            vectorSession = connectPinecone(runId);
            List<AutoMemoryVectorDocument> documents = fixture.memories().stream()
                    .map(memory -> AutoMemoryVectorDocument.current(memory, memory.version()))
                    .toList();
            List<float[]> embeddings = vectorSession.vectors().embedPassages(documents.stream()
                    .map(AutoMemoryVectorDocument::retrievalText)
                    .toList());
            List<AutoMemoryVector> projections = new ArrayList<>(documents.size());
            for (int index = 0; index < documents.size(); index++) {
                projections.add(new AutoMemoryVector(documents.get(index), embeddings.get(index)));
            }
            vectorIds = documents.stream().map(AutoMemoryVectorDocument::vectorId).toList();
            vectorSession.vectors().upsert(projections);
            waitUntilVisible(vectorSession.vectors(), vectorIds);
            waitUntilTargetsQueryable(vectorSession.vectors(), fixture, cohort.targets());

            DeepSeekPlanner planner = new DeepSeekPlanner();
            RecordingVectorSearch recordingVectors = new RecordingVectorSearch(
                    vectorSession.vectors());
            AutoMemoryContextSelector semanticSelector = new AutoMemoryContextSelector(
                    fixture.adapter(),
                    fixture.adapter(),
                    recordingVectors,
                    planner,
                    new AutoMemoryContextSelector.SemanticPolicy(0.82d, 0.02d, 0.03d),
                    new AutoMemoryContextSelector.Budget(12, 6_000));
            AutoMemoryContextSelector sqlSelector = new AutoMemoryContextSelector(
                    fixture.adapter(),
                    fixture.adapter(),
                    new AutoMemoryContextSelector.Budget(12, 6_000));

            Evaluation evaluation = evaluate(
                    cohort, fixture, semanticSelector, sqlSelector, recordingVectors, planner);
            Path report = reportPath(cohort.datasetVersion());
            writeReport(report, cohort, profile, vectorSession, planner, fixture, evaluation);

            assertTrue(!profile.enforceGate() || evaluation.passed(),
                    () -> "Auto Memory context E2E gate failed: " + evaluation.metrics()
                            + "; report=" + report.toAbsolutePath());
        } finally {
            try {
                if (vectorSession != null && !vectorIds.isEmpty()) {
                    vectorSession.vectors().delete(vectorIds);
                    waitUntilDeleted(vectorSession.vectors(), vectorIds);
                }
            } finally {
                cleanup(jdbc, fixture, runId);
            }
        }
    }

    private Evaluation evaluate(
            Cohort cohort,
            Fixture fixture,
            AutoMemoryContextSelector semanticSelector,
            AutoMemoryContextSelector sqlSelector,
            RecordingVectorSearch recordingVectors,
            DeepSeekPlanner planner
    ) {
        int expectedTotal = 0;
        int targetHits = 0;
        int selectedTotal = 0;
        int completePositive = 0;
        int positiveCases = 0;
        int negativeCases = 0;
        int negativeCasesWithInjection = 0;
        int forbiddenTotal = 0;
        int forbiddenHits = 0;
        int irrelevant = 0;
        int unauthorized = 0;
        int disabled = 0;
        int promptParity = 0;
        int sqlTargetHits = 0;
        List<CaseRun> runs = new ArrayList<>();

        for (CaseSpec testCase : cohort.cases()) {
            TurnKey turn = new TurnKey(
                    fixture.owner(), "memory-context-e2e", testCase.id());
            String readSetDigest = ModelInputBinding.digestOf(
                    cohort.datasetVersion(), testCase.id(), testCase.queryText());
            AutoMemoryContextQuery query = new AutoMemoryContextQuery(
                    turn,
                    testCase.chartbookContext() ? fixture.chartbook() : null,
                    testCase.queryText(),
                    ModelInputBinding.bound(
                            turn,
                            readSetDigest,
                            ModelInputBinding.digestOf(testCase.queryText(), readSetDigest)));
            recordingVectors.reset();
            AutoMemoryContextSelection selection = semanticSelector.select(query);
            AutoMemoryContextSelection sqlSelection = sqlSelector.select(query);
            List<String> selectedIds = selectedDatasetIds(selection, fixture);
            List<String> sqlSelectedIds = selectedDatasetIds(sqlSelection, fixture);
            Set<String> expected = Set.copyOf(testCase.expectedRelevantIds());
            Set<String> forbidden = Set.copyOf(testCase.forbiddenSelectedIds());
            long hits = selectedIds.stream().filter(expected::contains).count();
            long sqlHits = sqlSelectedIds.stream().filter(expected::contains).count();
            long caseForbidden = selectedIds.stream().filter(forbidden::contains).count();
            long caseUnauthorized = selection.references().stream()
                    .filter(reference -> fixture.unauthorizedIds().contains(reference.memoryId()))
                    .count();
            long caseDisabled = selection.references().stream()
                    .filter(reference -> fixture.disabledIds().contains(reference.memoryId()))
                    .count();
            String prompt = renderPrompt(turn, testCase, selection);
            List<String> promptLines = memoryLines(prompt);
            List<String> expectedLines = selection.context().entries().stream()
                    .map(entry -> (entry.scope() == AutoMemoryContext.Scope.CHARTBOOK
                            ? "chartbookMemory=" : "userMemory=") + entry.value())
                    .toList();
            boolean parity = promptLines.equals(expectedLines);

            expectedTotal += expected.size();
            targetHits += (int) hits;
            sqlTargetHits += (int) sqlHits;
            selectedTotal += selection.references().size();
            irrelevant += selection.references().size() - (int) hits;
            forbiddenTotal += forbidden.size();
            forbiddenHits += (int) caseForbidden;
            unauthorized += (int) caseUnauthorized;
            disabled += (int) caseDisabled;
            promptParity += parity ? 1 : 0;
            if (expected.isEmpty()) {
                negativeCases++;
                negativeCasesWithInjection += selection.empty() ? 0 : 1;
            } else {
                positiveCases++;
                completePositive += hits == expected.size() ? 1 : 0;
            }

            Set<String> rawUnauthorized = new LinkedHashSet<>();
            Set<String> rawDisabled = new LinkedHashSet<>();
            for (VectorSearchCall call : recordingVectors.calls()) {
                for (AutoMemoryVectorSearchHit hit : call.hits()) {
                    String memoryId = AutoMemoryVectorDocument.currentMemoryIdFromVectorId(
                            hit.vectorId()).orElse("");
                    if (fixture.unauthorizedIds().contains(memoryId)) {
                        rawUnauthorized.add(memoryId);
                    }
                    if (fixture.disabledIds().contains(memoryId)) {
                        rawDisabled.add(memoryId);
                    }
                }
            }
            runs.add(new CaseRun(
                    testCase.id(),
                    testCase.queryText(),
                    testCase.chartbookContext(),
                    testCase.expectedRelevantIds(),
                    selectedIds,
                    sqlSelectedIds,
                    promptLines,
                    parity,
                    planner.run(testCase.id()),
                    recordingVectors.calls(),
                    List.copyOf(rawUnauthorized),
                    List.copyOf(rawDisabled)));
        }

        Metrics metrics = new Metrics(
                ratio(targetHits, expectedTotal),
                ratio(completePositive, positiveCases),
                selectedTotal == 0 ? 1.0d : ratio(targetHits, selectedTotal),
                ratio(irrelevant, selectedTotal),
                ratio(negativeCasesWithInjection, negativeCases),
                ratio(forbiddenHits, forbiddenTotal),
                unauthorized,
                disabled,
                ratio(promptParity, cohort.cases().size()),
                ratio(sqlTargetHits, expectedTotal));
        Gate gate = cohort.gate();
        boolean passed = metrics.targetRecall() >= gate.minimumTargetRecall()
                && metrics.completePositiveCaseRate() >= gate.minimumCompletePositiveCaseRate()
                && metrics.promptPrecision() >= gate.minimumPromptPrecision()
                && metrics.irrelevantInjectionRate() <= gate.maximumIrrelevantInjectionRate()
                && metrics.negativeCaseInjectionRate() <= gate.maximumNegativeCaseInjectionRate()
                && metrics.forbiddenSelectionRate() <= gate.maximumForbiddenSelectionRate()
                && metrics.unauthorizedSelectionCount() <= gate.maximumUnauthorizedSelectionCount()
                && metrics.disabledSelectionCount() <= gate.maximumDisabledSelectionCount()
                && metrics.promptParityRate() >= gate.minimumPromptParityRate();
        return new Evaluation(metrics, List.copyOf(runs), passed);
    }

    private Fixture createFixture(
            JdbcTemplate jdbc,
            DriverManagerDataSource dataSource,
            String runId,
            Cohort cohort
    ) {
        String owner = runId + "_owner";
        String otherOwner = runId + "_other";
        String chartbook = runId + "_book";
        String otherChartbook = runId + "_other_book";
        jdbc.update("INSERT INTO chartbook (id, owner_key, name, status) "
                        + "VALUES (?, ?, 'Memory context E2E', 'ACTIVE')",
                chartbook, owner);
        jdbc.update("INSERT INTO chartbook (id, owner_key, name, status) "
                        + "VALUES (?, ?, 'Memory context E2E distractor', 'ACTIVE')",
                otherChartbook, owner);

        MySqlAutoMemoryAdapter adapter = new MySqlAutoMemoryAdapter(jdbc);
        TransactionTemplate transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        AutoMemoryObservationService observations = new AutoMemoryObservationService(adapter);
        List<AutoMemory> memories = new ArrayList<>();
        Map<String, String> datasetIdByMemoryId = new HashMap<>();
        Map<String, AutoMemory> targetByDatasetId = new LinkedHashMap<>();
        Set<String> unauthorizedIds = new HashSet<>();
        Set<String> disabledIds = new HashSet<>();

        for (int index = 0; index < cohort.targets().size(); index++) {
            TargetSpec target = cohort.targets().get(index);
            AutoMemoryScope scope = scope(target.scope(), owner, chartbook);
            AutoMemory memory = observe(
                    observations, transactions, scope, target.topic(), runId + "_target_" + index);
            setUpdatedAt(jdbc, memory.memoryId(), OLD.minus(Duration.ofDays(index)));
            memories.add(memory);
            datasetIdByMemoryId.put(memory.memoryId(), target.id());
            targetByDatasetId.put(target.id(), memory);

            // Identical content outside the authorized owner or Chartbook tests vector fencing.
            AutoMemoryScope unauthorizedScope = target.scope() == MemoryScopeType.USER
                    ? AutoMemoryScope.user(otherOwner)
                    : AutoMemoryScope.chartbook(owner, otherChartbook);
            AutoMemory unauthorized = observe(
                    observations,
                    transactions,
                    unauthorizedScope,
                    target.topic(),
                    runId + "_unauthorized_" + index);
            memories.add(unauthorized);
            unauthorizedIds.add(unauthorized.memoryId());
            datasetIdByMemoryId.put(unauthorized.memoryId(), "unauthorized-" + target.id());

            Topic disabledTopic = new Topic(
                    "disabled-" + target.semanticKey() + "-" + index,
                    target.title(),
                    target.canonicalText());
            AutoMemory activeCopy = observe(
                    observations,
                    transactions,
                    scope,
                    disabledTopic,
                    runId + "_disabled_" + index);
            AutoMemoryManagementOutcome outcome = transactions.execute(status -> adapter.disable(
                    new AutoMemoryFence(scope, activeCopy.memoryId(), activeCopy.version()),
                    Instant.now()));
            if (!(outcome instanceof AutoMemoryManagementOutcome.Updated updated)) {
                throw new IllegalStateException("failed to disable synthetic Memory: " + outcome);
            }
            AutoMemory disabled = updated.memory();
            memories.add(disabled);
            disabledIds.add(disabled.memoryId());
            datasetIdByMemoryId.put(disabled.memoryId(), "disabled-" + target.id());
        }

        for (MemoryScopeType type : List.of(MemoryScopeType.USER, MemoryScopeType.CHARTBOOK)) {
            AutoMemoryScope scope = scope(type, owner, chartbook);
            for (int index = 0; index < cohort.noise().size(); index++) {
                Topic source = cohort.noise().get(index);
                Topic noise = new Topic(
                        "noise-" + type.name().toLowerCase(Locale.ROOT) + "-" + index
                                + "-" + source.semanticKey(),
                        source.title(),
                        source.canonicalText());
                AutoMemory memory = observe(
                        observations,
                        transactions,
                        scope,
                        noise,
                        runId + "_noise_" + type.name().toLowerCase(Locale.ROOT) + "_" + index);
                setUpdatedAt(jdbc, memory.memoryId(), RECENT.plus(Duration.ofMinutes(index)));
                memories.add(memory);
                datasetIdByMemoryId.put(memory.memoryId(), noise.semanticKey());
            }
        }
        return new Fixture(
                owner,
                otherOwner,
                chartbook,
                otherChartbook,
                List.copyOf(memories),
                Map.copyOf(datasetIdByMemoryId),
                Map.copyOf(targetByDatasetId),
                Set.copyOf(unauthorizedIds),
                Set.copyOf(disabledIds),
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
                        new TurnKey(scope.ownerKey(), "memory-context-e2e", identity),
                        null,
                        MemoryObservationKind.EXPLICIT,
                        1.0d)));
        if (outcome instanceof AutoMemoryObservationOutcome.Applied applied) {
            return applied.memory();
        }
        throw new IllegalStateException("failed to create synthetic Memory: " + outcome);
    }

    private String renderPrompt(
            TurnKey turn,
            CaseSpec testCase,
            AutoMemoryContextSelection selection
    ) {
        ContextSlicePin memoryPin = selection.empty()
                ? ContextSlicePin.absent(ContextSlice.MEMORY, "empty-e2e-selection")
                : ContextSlicePin.pinned(
                        ContextSlice.MEMORY,
                        "memory-e2e-selection",
                        selection.version(),
                        selection.digest());
        ContextReadSet readSet = ContextReadSet.createWithMemorySelection(
                0,
                ContextSlicePin.absent(ContextSlice.SUMMARY, "e2e"),
                ContextSlicePin.absent(ContextSlice.MEMBERSHIP, "e2e"),
                ContextSlicePin.absent(ContextSlice.PROFILE, "e2e"),
                memoryPin,
                selection.references());
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        PlainGenerationRequest request = new PlainGenerationRequest(
                new FencedAttempt(
                        turn,
                        AttemptLease.fromDatabaseClock(
                                "attempt-" + testCase.id(),
                                1,
                                now,
                                now.plusSeconds(30),
                                30_000),
                        0,
                        ModelInputBinding.digestOf(testCase.queryText(), selection.digest()),
                        new ExecutionPolicySnapshot(
                                1, TurnEngineMode.V2_CANARY, "{}", "memory-context-e2e")),
                new BaseTurnContext(
                        new CurrentRequestContext(
                                turn.turnId(),
                                "memory-context-e2e-diagram",
                                new CurrentInstruction(testCase.queryText())),
                        new AbsentContext<>("e2e"),
                        new AbsentContext<>("e2e"),
                        new AbsentContext<>("e2e"),
                        new AbsentContext<>("e2e"),
                        new AbsentContext<>("e2e"),
                        new AbsentContext<>("e2e"),
                        new AbsentContext<>("e2e"),
                        new AvailableContext<>(selection.context(), "memory-context-e2e"),
                        new ContextDiagnostics(List.of())),
                readSet,
                new PlainDrawPlan(PlainDrawAction.CREATE, testCase.queryText()),
                PlainExecutionProfile.m2SourceFree());
        return new PlainGenerationPromptRenderer().render(request);
    }

    private List<String> memoryLines(String prompt) {
        return prompt.lines()
                .filter(line -> line.startsWith("userMemory=")
                        || line.startsWith("chartbookMemory="))
                .toList();
    }

    private List<String> selectedDatasetIds(
            AutoMemoryContextSelection selection,
            Fixture fixture
    ) {
        return selection.references().stream()
                .map(reference -> fixture.datasetIdByMemoryId().getOrDefault(
                        reference.memoryId(), "unknown-" + reference.memoryId()))
                .toList();
    }

    private void writeReport(
            Path path,
            Cohort cohort,
            Profile profile,
            VectorSession vectorSession,
            DeepSeekPlanner planner,
            Fixture fixture,
            Evaluation evaluation
    ) throws Exception {
        JSONObject report = new JSONObject(true);
        report.put("schemaVersion", "AUTO_MEMORY_CONTEXT_E2E_REPORT_V1");
        report.put("generatedAt", Instant.now().toString());
        report.put("datasetVersion", cohort.datasetVersion());
        report.put("datasetSha256", profile.sha256());
        report.put("plannerContractVersion", AutoMemoryRecallPlanningProtocol.CONTRACT_VERSION);
        report.put("plannerModel", planner.model());
        report.put("embeddingModel", vectorSession.embeddingModel());
        report.put("embeddingDimension", vectorSession.dimension());
        report.put("namespaceClass", "isolated-eval");
        report.put("productionBusinessWrites", 0);
        report.put("localFixtureMemoryCount", fixture.memories().size());
        report.put("finalDrawingModelCalls", 0);
        report.put("semanticPolicy", Map.of(
                "minimumScore", 0.82d,
                "minimumLead", 0.02d,
                "maximumScoreDrop", 0.03d));
        report.put("promptBudget", Map.of("maxEntries", 12, "maxCharacters", 6_000));
        report.put("gate", cohort.gate());
        report.put("metrics", evaluation.metrics());
        report.put("passed", evaluation.passed());
        report.put("runs", evaluation.runs());
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, JSON.toJSONString(
                report,
                SerializerFeature.PrettyFormat,
                SerializerFeature.DisableCircularReferenceDetect), StandardCharsets.UTF_8);
    }

    private VectorSession connectPinecone(String runId) {
        String baseNamespace = requiredEnvironment("AUTO_MEMORY_VECTOR_PINECONE_NAMESPACE");
        String normalized = baseNamespace.toLowerCase(Locale.ROOT);
        assertTrue(normalized.contains("test")
                        || normalized.contains("dev")
                        || normalized.contains("eval"),
                "context E2E evaluation requires a disposable namespace");
        String namespace = baseNamespace + "-" + runId;
        String model = environment(
                "AUTO_MEMORY_VECTOR_EMBEDDING_MODEL",
                environment("PINECONE_EMBEDDING_MODEL", "multilingual-e5-large"));
        int dimension = Integer.parseInt(environment(
                "AUTO_MEMORY_VECTOR_DIMENSION",
                environment("PINECONE_DIMENSION", "1024")));
        PineconeVectorClient client = new PineconeVectorClient(
                requiredEnvironment(
                        "AUTO_MEMORY_VECTOR_PINECONE_API_KEY", "PINECONE_API_KEY"),
                requiredEnvironment(
                        "AUTO_MEMORY_VECTOR_PINECONE_INDEX_HOST", "PINECONE_INDEX_HOST"),
                model,
                dimension,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                PineconeAutoMemoryVectorStoreAdapter.METADATA_FIELDS);
        return new VectorSession(
                new PineconeAutoMemoryVectorStoreAdapter(
                        client,
                        namespace,
                        requiredEnvironment(
                                "AUTO_MEMORY_VECTOR_PARTITION_SECRET",
                                "PINECONE_TENANT_HMAC_SECRET")),
                model,
                dimension);
    }

    private void waitUntilVisible(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            List<String> vectorIds
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            if (vectors.existingVectorIds(vectorIds).containsAll(vectorIds)) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("E2E vectors were not visible within 15 seconds");
    }

    private void waitUntilTargetsQueryable(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            Fixture fixture,
            List<TargetSpec> targets
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            boolean ready = true;
            for (TargetSpec target : targets) {
                AutoMemory memory = fixture.targetByDatasetId().get(target.id());
                String chartbookId = target.scope() == MemoryScopeType.CHARTBOOK
                        ? fixture.chartbook() : null;
                List<AutoMemoryVectorSearchHit> hits = vectors.search(
                        AutoMemoryVectorSearchQuery.activeContext(
                                new TurnKey(fixture.owner(), "memory-context-index", target.id()),
                                chartbookId,
                                target.canonicalText()),
                        32);
                String expectedVectorId = AutoMemoryVectorDocument.current(
                        memory, memory.version()).vectorId();
                if (hits.stream().noneMatch(hit -> hit.vectorId().equals(expectedVectorId))) {
                    ready = false;
                    break;
                }
            }
            if (ready) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("E2E target vectors were not queryable within 15 seconds");
    }

    private void waitUntilDeleted(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            List<String> vectorIds
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            if (vectors.existingVectorIds(vectorIds).isEmpty()) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("E2E vectors remained after cleanup");
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
                "context E2E evaluation requires a local MySQL URL");
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
        jdbc.update("DELETE FROM memory_item WHERE owner_key IN (?, ?)", owner, otherOwner);
        jdbc.update("DELETE FROM chartbook WHERE owner_key IN (?, ?)", owner, otherOwner);
    }

    private Cohort cohort(JSONObject root) {
        List<TargetSpec> targets = root.getJSONArray("targets").stream()
                .map(raw -> (JSONObject) raw)
                .map(value -> new TargetSpec(
                        value.getString("id"),
                        MemoryScopeType.valueOf(value.getString("scope")),
                        value.getString("semanticKey"),
                        value.getString("title"),
                        value.getString("canonicalText")))
                .toList();
        List<Topic> noise = root.getJSONArray("noise").stream()
                .map(raw -> (JSONObject) raw)
                .map(value -> new Topic(
                        value.getString("semanticKey"),
                        value.getString("title"),
                        value.getString("canonicalText")))
                .toList();
        List<CaseSpec> cases = root.getJSONArray("cases").stream()
                .map(raw -> (JSONObject) raw)
                .map(value -> new CaseSpec(
                        value.getString("id"),
                        value.getString("queryText"),
                        value.getBooleanValue("chartbookContext"),
                        value.getJSONArray("expectedRelevantIds").toJavaList(String.class),
                        value.getJSONArray("forbiddenSelectedIds").toJavaList(String.class)))
                .toList();
        JSONObject gate = root.getJSONObject("qualityGate");
        return new Cohort(
                root.getString("datasetVersion"),
                new Gate(
                        gate.getDoubleValue("minimumTargetRecall"),
                        gate.getDoubleValue("minimumCompletePositiveCaseRate"),
                        gate.getDoubleValue("minimumPromptPrecision"),
                        gate.getDoubleValue("maximumIrrelevantInjectionRate"),
                        gate.getDoubleValue("maximumNegativeCaseInjectionRate"),
                        gate.getDoubleValue("maximumForbiddenSelectionRate"),
                        gate.getIntValue("maximumUnauthorizedSelectionCount"),
                        gate.getIntValue("maximumDisabledSelectionCount"),
                        gate.getDoubleValue("minimumPromptParityRate")),
                targets,
                noise,
                cases);
    }

    private Profile profile() {
        return switch (environment(
                "AUTO_MEMORY_CONTEXT_E2E_EVALUATION_DATASET", "development-v1")) {
            case "development-v1" -> new Profile(
                    "auto-memory-context-e2e-development-v1/cohort.json",
                    DEVELOPMENT_SHA256,
                    false);
            case "holdout-v1" -> new Profile(
                    "auto-memory-context-e2e-v1/holdout.json",
                    HOLDOUT_SHA256,
                    true);
            default -> throw new IllegalArgumentException("unknown Memory context E2E dataset");
        };
    }

    private Path cohortPath(Profile profile) {
        String relative = "ai-agent-draw-io-infrastructure/src/test/resources/evals/"
                + profile.relativePath();
        for (Path candidate : List.of(Path.of(relative), Path.of("..").resolve(relative))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("Memory context E2E dataset was not found");
    }

    private Path reportPath(String datasetVersion) {
        String configured = System.getenv("AUTO_MEMORY_CONTEXT_E2E_EVALUATION_REPORT");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of("evaluation", "auto-memory-context-e2e-v1",
                datasetVersion + "-report.json").toAbsolutePath().normalize();
    }

    private AutoMemoryScope scope(MemoryScopeType type, String owner, String chartbook) {
        return type == MemoryScopeType.USER
                ? AutoMemoryScope.user(owner)
                : AutoMemoryScope.chartbook(owner, chartbook);
    }

    private String requiredEnvironment(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalStateException("required Memory context E2E environment is missing");
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String withoutLeadingSlash(String value) {
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0d : (double) numerator / denominator;
    }

    private record Profile(String relativePath, String sha256, boolean enforceGate) {
    }

    private record Topic(String semanticKey, String title, String canonicalText) {
    }

    private record TargetSpec(
            String id,
            MemoryScopeType scope,
            String semanticKey,
            String title,
            String canonicalText
    ) {
        private Topic topic() {
            return new Topic(semanticKey, title, canonicalText);
        }
    }

    private record CaseSpec(
            String id,
            String queryText,
            boolean chartbookContext,
            List<String> expectedRelevantIds,
            List<String> forbiddenSelectedIds
    ) {
    }

    private record Gate(
            double minimumTargetRecall,
            double minimumCompletePositiveCaseRate,
            double minimumPromptPrecision,
            double maximumIrrelevantInjectionRate,
            double maximumNegativeCaseInjectionRate,
            double maximumForbiddenSelectionRate,
            int maximumUnauthorizedSelectionCount,
            int maximumDisabledSelectionCount,
            double minimumPromptParityRate
    ) {
    }

    private record Cohort(
            String datasetVersion,
            Gate gate,
            List<TargetSpec> targets,
            List<Topic> noise,
            List<CaseSpec> cases
    ) {
    }

    private record Fixture(
            String owner,
            String otherOwner,
            String chartbook,
            String otherChartbook,
            List<AutoMemory> memories,
            Map<String, String> datasetIdByMemoryId,
            Map<String, AutoMemory> targetByDatasetId,
            Set<String> unauthorizedIds,
            Set<String> disabledIds,
            MySqlAutoMemoryAdapter adapter
    ) {
    }

    private record VectorSession(
            PineconeAutoMemoryVectorStoreAdapter vectors,
            String embeddingModel,
            int dimension
    ) {
    }

    private record PlannerRun(
            String status,
            List<String> queries,
            long latencyMillis,
            String failure
    ) {
    }

    private record VectorSearchCall(
            String queryText,
            List<AutoMemoryVectorSearchHit> hits
    ) {
    }

    private record CaseRun(
            String id,
            String queryText,
            boolean chartbookContext,
            List<String> expectedRelevantIds,
            List<String> selectedIds,
            List<String> sqlSelectedIds,
            List<String> promptMemoryLines,
            boolean promptParity,
            PlannerRun planner,
            List<VectorSearchCall> vectorSearches,
            List<String> rawUnauthorizedVectorHits,
            List<String> rawDisabledVectorHits
    ) {
    }

    private record Metrics(
            double targetRecall,
            double completePositiveCaseRate,
            double promptPrecision,
            double irrelevantInjectionRate,
            double negativeCaseInjectionRate,
            double forbiddenSelectionRate,
            int unauthorizedSelectionCount,
            int disabledSelectionCount,
            double promptParityRate,
            double sqlBaselineTargetRecall
    ) {
    }

    private record Evaluation(Metrics metrics, List<CaseRun> runs, boolean passed) {
    }

    private static final class RecordingVectorSearch implements AutoMemoryVectorSearchPort {
        private final AutoMemoryVectorSearchPort delegate;
        private final List<VectorSearchCall> calls = new ArrayList<>();

        private RecordingVectorSearch(AutoMemoryVectorSearchPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<AutoMemoryVectorSearchHit> search(
                AutoMemoryVectorSearchQuery query,
                int topK
        ) {
            List<AutoMemoryVectorSearchHit> hits = delegate.search(query, topK);
            calls.add(new VectorSearchCall(query.userContent(), hits));
            return hits;
        }

        private void reset() {
            calls.clear();
        }

        private List<VectorSearchCall> calls() {
            return List.copyOf(calls);
        }
    }

    /** Uses the same frozen protocol as the production adapter without starting Spring. */
    private final class DeepSeekPlanner implements AutoMemoryRecallPlanner {
        private final HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        private final AutoMemoryRecallPlanningEligibilityPolicy eligibility =
                new AutoMemoryRecallPlanningEligibilityPolicy();
        private final URI endpoint = URI.create(withoutTrailingSlash(environment(
                "AUTO_MEMORY_BASE_URL", "https://api.deepseek.com")) + "/"
                + withoutLeadingSlash(environment(
                "AUTO_MEMORY_COMPLETIONS_PATH", "v1/chat/completions")));
        private final String apiKey = requiredEnvironment(
                "AUTO_MEMORY_API_KEY", "LLM_API_KEY_deepseek");
        private final String model = environment("AUTO_MEMORY_MODEL", "deepseek-v4-pro");
        private final Map<String, PlannerRun> runs = new LinkedHashMap<>();

        @Override
        public List<String> plan(AutoMemoryContextQuery query) {
            String caseId = query.turn().turnId();
            if (!eligibility.shouldPlan(query.userContent())) {
                runs.put(caseId, new PlannerRun("SKIPPED", List.of(), 0, null));
                return List.of();
            }
            long started = System.nanoTime();
            try {
                JSONObject request = new JSONObject(true);
                JSONArray messages = new JSONArray();
                messages.add(message(
                        "system", AutoMemoryRecallPlanningProtocol.SYSTEM_INSTRUCTION.strip()));
                messages.add(message(
                        "user",
                        query.planningInputBinding().envelope(
                                AutoMemoryRecallPlanningProtocol.render(query))));
                request.put("model", model);
                request.put("messages", messages);
                request.put("max_tokens", MAX_TOKENS);
                request.put("temperature", 0);
                request.put("response_format", Map.of("type", "json_object"));
                request.put("stream", false);
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(endpoint)
                                .timeout(Duration.ofSeconds(60))
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        JSON.toJSONString(request), StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
                if (response.statusCode() != 200) {
                    String failure = "HTTP_" + response.statusCode();
                    runs.put(caseId, new PlannerRun("FAILED", List.of(), latency, failure));
                    throw new IllegalStateException(failure);
                }
                JSONObject body = JSON.parseObject(response.body());
                String output = body.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content");
                List<String> queries = AutoMemoryRecallPlanningProtocol.parse(output);
                runs.put(caseId, new PlannerRun("SUCCEEDED", queries, latency, null));
                return queries;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("planner interrupted", interrupted);
            } catch (RuntimeException failure) {
                runs.putIfAbsent(caseId, new PlannerRun(
                        "FAILED",
                        List.of(),
                        Duration.ofNanos(System.nanoTime() - started).toMillis(),
                        failure.getClass().getSimpleName()));
                throw failure;
            } catch (Exception failure) {
                runs.put(caseId, new PlannerRun(
                        "FAILED",
                        List.of(),
                        Duration.ofNanos(System.nanoTime() - started).toMillis(),
                        failure.getClass().getSimpleName()));
                throw new IllegalStateException("planner request failed", failure);
            }
        }

        private JSONObject message(String role, String content) {
            JSONObject message = new JSONObject(true);
            message.put("role", role);
            message.put("content", content);
            return message;
        }

        private PlannerRun run(String caseId) {
            return runs.getOrDefault(caseId, new PlannerRun("NOT_CALLED", List.of(), 0, null));
        }

        private String model() {
            return model;
        }
    }
}
