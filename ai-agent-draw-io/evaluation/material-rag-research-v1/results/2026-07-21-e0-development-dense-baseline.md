# E0 baseline — development split, dense-only retrieval

First real E0 measurement on the frozen v2 corpus (core 240, commit `14141132`).
This is the **dense candidate-retrieval baseline only** (no lexical / hybrid / rerank);
it is a diagnostic starting point, not a passing result.

## Run configuration

- Split: `development` (answerable text/table anchors only, per the dense test filter).
- Retrieval: Pinecone integrated `multilingual-e5-large`, dim 1024, top-40 candidates, `tenant_key` filter.
- Chunking: `CanonicalPageAssembler(0.70)` at HEAD (in-flight local change stashed so the baseline is clean).
- Corpus/projections rebuilt live from the committed PDFs; 3 scenario families added to the projection set.
- Indexed chunks: **724**. Test: `ControlledPdfDenseRecallLiveTest#shouldMeasureDenseRecallAfterTheRealPdfAndChunkPipeline`.
- Starter-tier Pinecone needed a longer index-visibility wait (`MATERIAL_RAG_INDEX_WAIT_ATTEMPTS=240`, delete=120); the 12s default was too short and caused a false "not searchable" error on the first attempt.

## Headline metrics vs V2 gates

| Metric | Value | V2 gate | Pass? |
|---|---|---|---|
| Recall@1 | 0.5645 | — | — |
| Recall@5 | 0.8065 | — | — |
| Recall@10 | **0.8387** | ≥ 0.90 | ❌ |
| Recall@40 | **0.9194** | ≥ 0.95 | ❌ |
| MRR@10 | 0.6625 | ≥ 0.75 | ❌ |

Both recall gates and MRR are below target — expected for a dense-only baseline and exactly the gap the later experiments (E2 chunking, E3 retrieval/hybrid, E5 rerank) are meant to close.

## Slices

| Slice | n | R@10 | MRR@10 |
|---|--:|--:|--:|
| language: crossLanguage | 25 | 0.9200 | 0.7613 |
| language: zh | 24 | 0.7917 | 0.6465 |
| language: en | 13 | **0.7692** | 0.5021 |
| category: table | 9 | 0.8889 | 0.7593 |
| category: text | 44 | 0.8409 | 0.7223 |
| category: multi_evidence | 9 | **0.7778** | **0.2735** |

## Where it loses evidence

Hard misses (gold never retrieved, rank 0): `controlled-039` (hgr-isolation-time),
`controlled-121` (dwh-sequence-type), `controlled-231` (water-version-reject),
`controlled-236` (daa-version-superseded), `controlled-238` (daa-chartbook-first).

Retrieved but far down: water-threshold (rank 27), daa-approval (rank 22),
multi-evidence pairs at ranks 9–24.

## Diagnosis / hypotheses for next experiments

1. **Abstract policy anchors are the weakest.** Three of five hard misses are the
   version/retrieval-decision supplement anchors (rejected-draft / source-order rules).
   The query asks about a decision ("was the draft adopted?") while the gold sentence
   states the rule obliquely — a big dense semantic gap. → likely helped by lexical/hybrid
   (E3) and by anchoring evidence text closer to the decision phrasing (E1/E2).
2. **Multi-evidence is the weakest category** (MRR 0.27): dense retrieval surfaces one
   part of the pair but not both within the budget. → E4/E6 (dedup, context budget) and
   ALL_PARTS-aware scoring.
3. **English monolingual lags** cross-language and even zh here; worth confirming it's not
   a small-n artifact (n=13) before acting.
4. Isolation-time / exact-timestamp anchors miss — dense struggles with near-duplicate
   timestamps; exact-identifier lexical retrieval should recover these.

## Reproduce

```bash
cd ai-agent-draw-io && set -a; source .env; set +a
export MATERIAL_RAG_TOKENIZER_PATH="$PWD/tmp/material-rag-tokenizer/tokenizer.json"
export MATERIAL_RAG_RESEARCH_SPLIT=development
export MATERIAL_RAG_INDEX_WAIT_ATTEMPTS=240 MATERIAL_RAG_DELETE_WAIT_ATTEMPTS=120
mvn -q -pl ai-agent-draw-io-ingestion-worker -am \
  -Dtest='ControlledPdfDenseRecallLiveTest#shouldMeasureDenseRecallAfterTheRealPdfAndChunkPipeline' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Vectors are upserted under a unique `test`/`dev` namespace prefix and deleted in the same run.
