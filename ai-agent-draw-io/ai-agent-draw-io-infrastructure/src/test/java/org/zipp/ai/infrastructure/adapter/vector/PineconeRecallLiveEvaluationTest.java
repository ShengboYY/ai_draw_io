package org.zipp.ai.infrastructure.adapter.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in bilingual Pinecone recall smoke evaluation against a disposable tenant corpus. */
class PineconeRecallLiveEvaluationTest {

    private static final List<EvaluationCase> CASES = List.of(
            new EvaluationCase("agile-retro",
                    "A sprint retrospective happens after the sprint review. The team inspects how it worked and agrees improvement actions for the next sprint.",
                    "When does a Scrum team discuss how it worked and choose improvements?"),
            new EvaluationCase("agile-planning",
                    "Sprint planning starts the sprint. The product owner and developers define the sprint goal and select backlog items.",
                    "Which Scrum event defines the sprint goal and selects backlog work?"),
            new EvaluationCase("agile-daily",
                    "The daily scrum is a fifteen-minute event for developers to inspect progress toward the sprint goal and adapt their plan.",
                    "What is the fifteen-minute event for adapting the daily plan?"),
            new EvaluationCase("oauth-refresh",
                    "An OAuth refresh token obtains a new access token without asking the user to authenticate again. It must be stored securely.",
                    "Which OAuth credential renews access without another login?"),
            new EvaluationCase("oauth-access",
                    "An OAuth access token authorizes API requests and is normally short lived. A resource server validates it before returning protected data.",
                    "Which short-lived OAuth credential is sent to a protected API?"),
            new EvaluationCase("mysql-mvcc",
                    "MySQL InnoDB uses multi-version concurrency control so consistent reads can observe a snapshot without blocking ordinary writers.",
                    "How can InnoDB snapshot reads avoid blocking writers?"),
            new EvaluationCase("mysql-index",
                    "A composite MySQL B-tree index is most effective when predicates use its leftmost columns in index order.",
                    "What rule controls whether a composite B-tree index can serve a query?"),
            new EvaluationCase("pdf-ocr",
                    "A scanned PDF contains page images rather than selectable text. OCR must run before its words can participate in retrieval.",
                    "Why must OCR run before searching a scanned PDF?"),
            new EvaluationCase("invoice-ocr",
                    "发票扫描件经过 OCR 后，需要保留金额、税号和开票日期所在的页面坐标，便于回答时追踪来源。",
                    "识别扫描发票时，金额和税号为什么还要保留页面坐标？"),
            new EvaluationCase("pdf-visual",
                    "PDF 中的流程图可能通过箭头表达正文没有描述的先后关系，因此总结整份文档时要选择重要图表参与视觉理解。",
                    "为什么总结 PDF 时不能只处理正文而忽略流程图箭头？"),
            new EvaluationCase("version-pinned",
                    "已经创建的图表如果引用资料 V1，即使资料后来出现 V2，也继续固定使用 V1，并提醒用户存在新版本。",
                    "旧图表引用 V1 后资料更新为 V2，系统应该使用哪个版本？"),
            new EvaluationCase("version-latest",
                    "创建新图表且没有明确指定资料版本时，系统默认检索该资料当前可用的最新版本。",
                    "新图表没有指定版本时应该检索旧版本还是最新版本？"),
            new EvaluationCase("rag-citation",
                    "RAG 生成的事实性回答必须绑定证据标识，引用需要指向实际支持该结论的文档页面或视觉区域。",
                    "RAG 回答中的引用应该绑定到什么位置？"),
            new EvaluationCase("style-no-search",
                    "仅要求改变图表颜色、字体或视觉风格时不应触发资料检索，因为该请求不需要事实证据。",
                    "用户只要求改变图表颜色时需要检索资料库吗？"),
            new EvaluationCase("drawio-swimlane",
                    "泳道图按照角色或部门划分泳道，用跨泳道箭头展示任务交接和流程责任。",
                    "哪种图适合展示不同部门之间的任务交接？"),
            new EvaluationCase("drawio-architecture",
                    "系统架构图展示客户端、服务、数据库和外部供应商之间的依赖关系，不用于表达部门责任泳道。",
                    "什么图适合说明服务、数据库和外部供应商的依赖？")
    );

    @Test
    void shouldMeetBilingualRecallThresholdsWithoutCrossTenantLeakage() throws Exception {
        String apiKey = System.getenv("PINECONE_API_KEY");
        String indexHost = System.getenv("PINECONE_INDEX_HOST");
        String namespace = System.getenv().getOrDefault("PINECONE_NAMESPACE", "recall-test");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank()
                && indexHost != null && !indexHost.isBlank());
        // Live evaluation must never write its disposable corpus into the production namespace.
        Assumptions.assumeTrue(namespace.toLowerCase(Locale.ROOT).contains("test")
                || namespace.toLowerCase(Locale.ROOT).contains("dev"));

        PineconeVectorClient client = new PineconeVectorClient(
                apiKey, indexHost, "multilingual-e5-large", 1024, new ObjectMapper());
        String runId = "recall_" + UUID.randomUUID().toString().replace("-", "");
        String tenantKey = runId + "_tenant";
        List<String> vectorIds = CASES.stream().map(value -> runId + "_" + value.id()).toList();

        try {
            List<float[]> passageVectors = client.embed(
                    CASES.stream().map(EvaluationCase::passage).toList(), "passage");
            List<PineconeVectorRecord> records = new ArrayList<>();
            for (int index = 0; index < CASES.size(); index++) {
                records.add(new PineconeVectorRecord(vectorIds.get(index), passageVectors.get(index),
                        metadata(tenantKey, vectorIds.get(index), index + 1)));
            }
            client.upsert(namespace, records);
            waitUntilVisible(client, namespace, vectorIds);
            waitUntilSearchable(client, namespace, tenantKey, vectorIds, passageVectors);

            List<float[]> queryVectors = embedQueries(client);
            RetrievalMetrics metrics = evaluate(client, namespace, tenantKey, vectorIds, queryVectors);
            System.out.printf(Locale.ROOT,
                    "Pinecone bilingual recall: Recall@1=%.4f Recall@5=%.4f Recall@10=%.4f MRR@10=%.4f cases=%d%n",
                    metrics.recallAt1(), metrics.recallAt5(), metrics.recallAt10(), metrics.mrrAt10(), CASES.size());
            if (!metrics.missedCases().isEmpty()) {
                System.out.println("Pinecone missed evaluation cases: " + metrics.missedCases());
            }

            assertTrue(metrics.recallAt5() >= 0.85, "Recall@5 fell below the smoke threshold");
            assertTrue(metrics.recallAt10() >= 0.90, "Recall@10 fell below the Beta text threshold");
            assertTrue(metrics.mrrAt10() >= 0.75, "MRR@10 fell below the smoke threshold");
            assertTrue(client.query(namespace, queryVectors.get(0), 10,
                    Map.of("tenant_key", Map.of("$eq", runId + "_other_tenant"))).isEmpty(),
                    "A query escaped its tenant boundary");
        } finally {
            // Every vector uses a unique run prefix, so cleanup cannot remove another test run's data.
            client.delete(namespace, vectorIds);
            waitUntilDeleted(client, namespace, vectorIds);
        }
    }

    private List<float[]> embedQueries(PineconeVectorClient client) {
        List<float[]> vectors = new ArrayList<>();
        List<String> queries = CASES.stream().map(EvaluationCase::query).toList();
        // Pinecone currently accepts at most three E5 query inputs in one inference request.
        for (int start = 0; start < queries.size(); start += 3) {
            vectors.addAll(client.embed(queries.subList(start, Math.min(start + 3, queries.size())), "query"));
        }
        return List.copyOf(vectors);
    }

    private RetrievalMetrics evaluate(PineconeVectorClient client, String namespace, String tenantKey,
                                      List<String> goldIds, List<float[]> queryVectors) {
        int hitsAt1 = 0;
        int hitsAt5 = 0;
        int hitsAt10 = 0;
        double reciprocalRanks = 0.0;
        List<String> missedCases = new ArrayList<>();
        for (int index = 0; index < queryVectors.size(); index++) {
            List<String> matches = client.query(namespace, queryVectors.get(index), 10,
                    Map.of("tenant_key", Map.of("$eq", tenantKey)));
            int rank = matches.indexOf(goldIds.get(index));
            if (rank == 0) hitsAt1++;
            if (rank >= 0 && rank < 5) hitsAt5++;
            if (rank >= 0 && rank < 10) {
                hitsAt10++;
                reciprocalRanks += 1.0 / (rank + 1);
            } else {
                missedCases.add(CASES.get(index).id());
            }
        }
        double total = queryVectors.size();
        return new RetrievalMetrics(hitsAt1 / total, hitsAt5 / total,
                hitsAt10 / total, reciprocalRanks / total, List.copyOf(missedCases));
    }

    private void waitUntilVisible(PineconeVectorClient client, String namespace,
                                  List<String> vectorIds) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (client.fetchExisting(namespace, vectorIds).containsAll(vectorIds)) return;
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Pinecone vectors were not visible within 10 seconds");
    }

    private void waitUntilSearchable(PineconeVectorClient client, String namespace, String tenantKey,
                                     List<String> vectorIds, List<float[]> passageVectors)
            throws InterruptedException {
        // Fetch visibility can precede ANN visibility, so verify every disposable vector is searchable.
        for (int attempt = 0; attempt < 20; attempt++) {
            boolean allSearchable = true;
            for (int index = 0; index < vectorIds.size(); index++) {
                if (!client.query(namespace, passageVectors.get(index), 1,
                        Map.of("tenant_key", Map.of("$eq", tenantKey))).contains(vectorIds.get(index))) {
                    allSearchable = false;
                    break;
                }
            }
            if (allSearchable) return;
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Pinecone vectors were not searchable within 10 seconds");
    }

    private void waitUntilDeleted(PineconeVectorClient client, String namespace,
                                  List<String> vectorIds) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (client.fetchExisting(namespace, vectorIds).isEmpty()) return;
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Pinecone test vectors remained after cleanup");
    }

    private Map<String, Object> metadata(String tenantKey, String vectorId, int pageNo) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenant_key", tenantKey);
        metadata.put("material_id", "mat_" + vectorId);
        metadata.put("version_id", "ver_" + vectorId);
        metadata.put("revision_id", "rev_" + vectorId);
        metadata.put("retrieval_chunk_id", vectorId);
        metadata.put("chunk_type", "CONTENT");
        metadata.put("modality", "TEXT");
        metadata.put("page_no", pageNo);
        metadata.put("language", pageNo <= 8 ? "en" : "zh");
        metadata.put("index_generation_id", "ig_recall_live_v1");
        return metadata;
    }

    private record EvaluationCase(String id, String passage, String query) { }

    private record RetrievalMetrics(double recallAt1, double recallAt5, double recallAt10,
                                    double mrrAt10, List<String> missedCases) { }
}
