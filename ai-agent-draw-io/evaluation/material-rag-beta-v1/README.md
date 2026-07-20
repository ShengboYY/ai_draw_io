# Material RAG Beta evaluation dataset

This directory defines the source-controlled contract for WP8 RAG evaluation cases. Cases must use
`rag-eval-case-v1`, synthetic owner aliases, immutable fixture version aliases, gold Evidence IDs,
hard negatives, required facets and forbidden sources.

The checked-in seed cases verify the schema only. They are deliberately not presented as a complete
release dataset. Before Beta, publish at least 165 reviewed cases matching `dataset-plan.json`; at
least 50 must be locked and excluded from tuning. A release report is accepted only by
`RagBetaReleaseGate`, so seed data cannot accidentally approve production.

Release flow:

1. Materialize synthetic documents and owner/scope fixtures outside production storage.
2. Publish reviewed cases through the existing evaluation control plane.
3. Run the locked dataset against the pinned processing, ranking and model profiles.
4. Produce `rag-release-report-v1` with immutable report/dataset/processing/ranking/model/deployment
   profile IDs, every `RagReleaseMetric`, all four security counts, and no-answer calibration slices.
   Each slice records raw rates, confidence intervals and its actual 30/40/50/100 fallback cohort.
5. Have a Release Owner submit the report to
   `POST /api/v1/admin/material-capabilities/release-gate`.
6. Pin the returned `rag-approval-v1:<sha256>` in `MATERIAL_RELEASE_REPORT_VERSION`; never set approval
   from an unversioned local result or from the seed dataset.

## Local Pinecone smoke evaluation

Use a non-production namespace whose name contains `test` or `dev`. Load the backend `.env`, then run:

```bash
set -a
source .env
set +a
mvn -q -pl ai-agent-draw-io-infrastructure -am \
  -Dtest=PineconeVectorClientLiveContractTest,PineconeRecallLiveEvaluationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The live evaluation writes a unique disposable bilingual corpus, waits separately for fetch and ANN
visibility, measures Recall@1/5/10 and MRR@10, verifies tenant isolation, and deletes every test vector.
It is a connectivity and retrieval-quality smoke test only; it does not replace the 165-case reviewed
Beta dataset or end-to-end PDF/OCR/visual evaluation.
