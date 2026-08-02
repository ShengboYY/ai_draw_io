package org.zipp.ai.infrastructure.turn.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.zipp.ai.application.memory.AutoMemory;
import org.zipp.ai.application.memory.AutoMemoryConsolidationQuery;
import org.zipp.ai.application.memory.AutoMemoryScope;
import org.zipp.ai.application.memory.AutoMemoryStatus;
import org.zipp.ai.application.memory.AutoMemoryType;
import org.zipp.ai.application.memory.AutoMemoryVector;
import org.zipp.ai.application.memory.AutoMemoryVectorDocument;
import org.zipp.ai.application.memory.MemoryScopeType;
import org.zipp.ai.application.turn.TurnKey;
import org.zipp.ai.infrastructure.adapter.vector.PineconeAutoMemoryVectorStoreAdapter;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorClient;
import org.zipp.ai.infrastructure.adapter.vector.PineconeVectorRecord;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in release gate that exercises the production Memory adapter against disposable vectors. */
@EnabledIfEnvironmentVariable(
        named = "AUTO_MEMORY_VECTOR_LIVE_EVALUATION_ENABLED",
        matches = "true")
class AutoMemoryPineconeLiveEvaluationTest {

    @Test
    void pineconeCohortMeetsSemanticIsolationAndLifecycleGates() throws Exception {
        AutoMemoryRetrievalCohort.Dataset dataset = AutoMemoryRetrievalCohort.load();
        AutoMemoryPineconeLiveTestSupport.Session pinecone =
                AutoMemoryPineconeLiveTestSupport.connect(new ObjectMapper());
        String partitionSecret = requiredEnvironment(
                "AUTO_MEMORY_VECTOR_PARTITION_SECRET", "PINECONE_TENANT_HMAC_SECRET");
        PineconeVectorClient client = pinecone.client();
        PineconeAutoMemoryVectorStoreAdapter vectors = pinecone.vectors();
        String runId = "ameval_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12);
        Corpus corpus = corpus(dataset, runId, partitionSecret);

        try {
            List<float[]> values = vectors.embedPassages(corpus.entries().stream()
                    .map(CorpusEntry::retrievalText)
                    .toList());
            List<AutoMemoryVector> eligible = new ArrayList<>();
            List<PineconeVectorRecord> staleTerminal = new ArrayList<>();
            for (int index = 0; index < corpus.entries().size(); index++) {
                CorpusEntry entry = corpus.entries().get(index);
                if (entry.document() != null) {
                    eligible.add(new AutoMemoryVector(entry.document(), values.get(index)));
                } else {
                    staleTerminal.add(new PineconeVectorRecord(
                            entry.vectorId(), values.get(index), entry.terminalMetadata()));
                }
            }
            vectors.upsert(eligible);
            client.upsert(pinecone.namespace(), staleTerminal);
            AutoMemoryPineconeLiveTestSupport.waitUntilVisible(vectors, corpus.vectorIds());
            waitUntilIndexed(corpus, vectors);

            QueryResult result = query(dataset, corpus, vectors);
            AutoMemoryRetrievalCohort.Metrics metrics = AutoMemoryRetrievalCohort.evaluate(
                    dataset, result.rankings());
            boolean passed = metrics.passes(dataset.gate());
            Path reportPath = reportPath();
            writeReport(
                    reportPath,
                    dataset,
                    pinecone.model(),
                    pinecone.dimension(),
                    metrics,
                    passed,
                    result.caseReports());

            assertTrue(passed, () -> "Auto Memory Pinecone retrieval gate failed: "
                    + metrics + "; report=" + reportPath.toAbsolutePath());
        } finally {
            // Run-specific vector IDs and partitions make this cleanup safe under concurrent evals.
            vectors.delete(corpus.vectorIds());
            AutoMemoryPineconeLiveTestSupport.waitUntilDeleted(vectors, corpus.vectorIds());
        }
    }

    private Corpus corpus(
            AutoMemoryRetrievalCohort.Dataset dataset,
            String runId,
            String partitionSecret
    ) {
        List<CorpusEntry> entries = new ArrayList<>();
        Map<String, String> vectorToCandidate = new LinkedHashMap<>();
        Map<String, CaseIdentity> cases = new LinkedHashMap<>();
        int candidateIndex = 0;
        for (int caseIndex = 0; caseIndex < dataset.cases().size(); caseIndex++) {
            AutoMemoryRetrievalCohort.EvaluationCase testCase = dataset.cases().get(caseIndex);
            String casePartition = runId + "_c" + caseIndex;
            CaseIdentity identity = new CaseIdentity(
                    mappedOwner(casePartition, testCase.ownerKey()),
                    mappedChartbook(casePartition, testCase.chartbookScopeKey()));
            cases.put(testCase.id(), identity);
            for (AutoMemoryRetrievalCohort.Candidate candidate : testCase.candidates()) {
                AutoMemoryScope scope = mappedScope(casePartition, candidate.scope());
                String memoryId = runId + "_m" + candidateIndex;
                String retrievalText = candidate.title() + "\n" + candidate.canonicalText();
                if (candidate.eligible()) {
                    AutoMemoryVectorDocument document = document(candidate, memoryId, scope);
                    entries.add(new CorpusEntry(
                            document.vectorId(), retrievalText, document, Map.of()));
                    vectorToCandidate.put(document.vectorId(), candidate.id());
                } else {
                    String vectorId = runId + ".terminal." + candidateIndex;
                    entries.add(new CorpusEntry(
                            vectorId,
                            retrievalText,
                            null,
                            terminalMetadata(candidate, scope, partitionSecret)));
                    vectorToCandidate.put(vectorId, candidate.id());
                }
                candidateIndex++;
            }
        }
        return new Corpus(
                List.copyOf(entries),
                Map.copyOf(vectorToCandidate),
                Map.copyOf(cases));
    }

    private AutoMemoryVectorDocument document(
            AutoMemoryRetrievalCohort.Candidate candidate,
            String memoryId,
            AutoMemoryScope scope
    ) {
        if ("CHALLENGER".equals(candidate.kind())) {
            return AutoMemoryVectorDocument.challenger(
                    memoryId, scope, candidate.title(), candidate.canonicalText(), 1);
        }
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        AutoMemory memory = new AutoMemory(
                memoryId,
                scope,
                AutoMemoryType.PREFERENCE,
                candidate.semanticKey(),
                candidate.title(),
                candidate.canonicalText(),
                AutoMemoryStatus.valueOf(candidate.state()),
                0.9d,
                2,
                false,
                1,
                now,
                now);
        return AutoMemoryVectorDocument.current(memory, 1);
    }

    private void waitUntilIndexed(
            Corpus corpus,
            PineconeAutoMemoryVectorStoreAdapter vectors
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            boolean allIndexed = true;
            for (CorpusEntry entry : corpus.entries()) {
                if (entry.document() == null) {
                    continue;
                }
                AutoMemoryScope scope = entry.document().scope();
                AutoMemoryConsolidationQuery probe = new AutoMemoryConsolidationQuery(
                        new TurnKey(scope.ownerKey(), "memory-vector-eval", entry.vectorId()),
                        scope.chartbookId(),
                        entry.retrievalText(),
                        16);
                if (!vectors.search(probe, 32).contains(entry.vectorId())) {
                    allIndexed = false;
                    break;
                }
            }
            if (allIndexed) {
                return;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Memory vectors were not fully indexed within 10 seconds");
    }

    private QueryResult query(
            AutoMemoryRetrievalCohort.Dataset dataset,
            Corpus corpus,
            PineconeAutoMemoryVectorStoreAdapter vectors
    ) {
        Map<String, List<String>> rankings = new LinkedHashMap<>();
        JSONArray reports = new JSONArray();
        for (AutoMemoryRetrievalCohort.EvaluationCase testCase : dataset.cases()) {
            CaseIdentity identity = corpus.cases().get(testCase.id());
            AutoMemoryConsolidationQuery query = new AutoMemoryConsolidationQuery(
                    new TurnKey(identity.ownerKey(), "memory-vector-eval", testCase.id()),
                    identity.chartbookId(),
                    testCase.queryText(),
                    16);
            long started = System.nanoTime();
            List<String> vectorIds = vectors.search(query, testCase.topK());
            long latencyMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            List<String> candidateIds = new ArrayList<>();
            for (int index = 0; index < vectorIds.size(); index++) {
                candidateIds.add(corpus.vectorToCandidate().getOrDefault(
                        vectorIds.get(index), "UNKNOWN_VECTOR_" + index));
            }
            List<String> ranking = List.copyOf(candidateIds);
            rankings.put(testCase.id(), ranking);

            JSONObject report = new JSONObject(true);
            report.put("caseId", testCase.id());
            report.put("tags", testCase.tags());
            report.put("topK", testCase.topK());
            report.put("expectedRelevantIds", testCase.expectedRelevantIds());
            report.put("ranking", ranking);
            int firstRelevant = firstRelevantRank(testCase.expectedRelevantIds(), ranking);
            report.put("firstRelevantRank", firstRelevant < 0 ? null : firstRelevant + 1);
            report.put("latencyMillis", latencyMillis);
            reports.add(report);
        }
        return new QueryResult(Map.copyOf(rankings), reports);
    }

    private void writeReport(
            Path path,
            AutoMemoryRetrievalCohort.Dataset dataset,
            String model,
            int dimension,
            AutoMemoryRetrievalCohort.Metrics metrics,
            boolean passed,
            JSONArray cases
    ) throws Exception {
        JSONObject report = new JSONObject(true);
        report.put("schemaVersion", "AUTO_MEMORY_RETRIEVAL_REPORT_V1");
        report.put("generatedAt", Instant.now().toString());
        report.put("datasetVersion", dataset.datasetVersion());
        report.put("retrievalContractVersion", dataset.retrievalContractVersion());
        report.put("embeddingModel", model);
        report.put("dimension", dimension);
        report.put("gate", Map.of(
                "minimumRelevantRecallAtK", dataset.gate().minimumRelevantRecallAtK(),
                "minimumDisabledRecallAtK", dataset.gate().minimumDisabledRecallAtK(),
                "maximumUnauthorizedRecallRate", dataset.gate().maximumUnauthorizedRecallRate(),
                "maximumIneligibleRecallRate", dataset.gate().maximumIneligibleRecallRate()));
        report.put("metrics", Map.of(
                "relevantRecallAtK", metrics.relevantRecallAtK(),
                "disabledRecallAtK", metrics.disabledRecallAtK(),
                "top1RelevantRate", metrics.top1RelevantRate(),
                "meanReciprocalRank", metrics.meanReciprocalRank(),
                "unauthorizedRecallRate", metrics.unauthorizedRecallRate(),
                "ineligibleRecallRate", metrics.ineligibleRecallRate(),
                "unknownReturnCount", metrics.unknownReturnCount()));
        report.put("passed", passed);
        report.put("cases", cases);
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(
                path,
                JSON.toJSONString(
                        report,
                        SerializerFeature.PrettyFormat,
                        SerializerFeature.DisableCircularReferenceDetect),
                StandardCharsets.UTF_8);
    }

    private Map<String, Object> terminalMetadata(
            AutoMemoryRetrievalCohort.Candidate candidate,
            AutoMemoryScope scope,
            String secret
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("memory_owner_partition", hmac(
                secret, "memory-owner\u001f" + scope.ownerKey()));
        metadata.put("memory_scope_partition", hmac(
                secret, "memory-scope\u001f" + scope.ownerKey() + "\u001f"
                        + scope.type().name() + "\u001f" + scope.scopeKey()));
        metadata.put("memory_scope_type", scope.type().name());
        metadata.put("memory_candidate_kind", candidate.kind());
        metadata.put("memory_candidate_state", candidate.state());
        metadata.put("memory_projection_revision", 1);
        return Map.copyOf(metadata);
    }

    private int firstRelevantRank(List<String> expected, List<String> ranking) {
        for (int index = 0; index < ranking.size(); index++) {
            if (expected.contains(ranking.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private String hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required by the JVM", impossible);
        }
    }

    private AutoMemoryScope mappedScope(
            String casePartition,
            AutoMemoryRetrievalCohort.ScopeRef scope
    ) {
        String owner = mappedOwner(casePartition, scope.ownerKey());
        if (scope.scopeType() == MemoryScopeType.USER) {
            return AutoMemoryScope.user(owner);
        }
        return AutoMemoryScope.chartbook(owner, mappedChartbook(casePartition, scope.scopeKey()));
    }

    private String mappedOwner(String casePartition, String ownerKey) {
        return casePartition + "_" + ownerKey;
    }

    private String mappedChartbook(String casePartition, String chartbookId) {
        return chartbookId == null ? null : casePartition + "_" + chartbookId;
    }

    private Path reportPath() {
        String configured = System.getenv("AUTO_MEMORY_VECTOR_RETRIEVAL_REPORT");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of("target", "auto-memory-retrieval", "pinecone-v2.json");
    }

    private String requiredEnvironment(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalStateException("required Memory vector environment is missing");
    }

    private record CorpusEntry(
            String vectorId,
            String retrievalText,
            AutoMemoryVectorDocument document,
            Map<String, Object> terminalMetadata
    ) {
    }

    private record Corpus(
            List<CorpusEntry> entries,
            Map<String, String> vectorToCandidate,
            Map<String, CaseIdentity> cases
    ) {
        private List<String> vectorIds() {
            return entries.stream().map(CorpusEntry::vectorId).toList();
        }
    }

    private record CaseIdentity(String ownerKey, String chartbookId) {
    }

    private record QueryResult(Map<String, List<String>> rankings, JSONArray caseReports) {
    }
}
