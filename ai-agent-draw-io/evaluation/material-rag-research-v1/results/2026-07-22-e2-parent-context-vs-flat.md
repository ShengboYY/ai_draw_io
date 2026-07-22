# E2 Development: flat leaf vs parent-context-500

E2 used **155** answerable dense-eligible Development cases. Holdout and Validation remained sealed.
Both runs used commit `0daa5d3e`, E1 canonical mode `e1-v5`, the same 533 child IDs,
`multilingual-e5-large`, top 40 and corpus-lock snapshot
`2026-07-22-e2-run-corpus-lock.json` (SHA-256 `46ebc064…`).

The flat baseline embedded each child `retrievalText`. The candidate embedded the child's existing
same-page, same-section neighbor context when it was at most 500 local-tokenizer tokens, otherwise it
fell back to the child text. Retrieval and scoring still resolved to the child ID.

| Profile | Flat | Parent-context-500 |
|---|---:|---:|
| Searchable chunks | 533 | 533 |
| Embedding tokens p50 / p95 / max | 105 / 356 / 419 | 272 / 383 / 495 |
| Gold mapping | 149/155 (0.961) | 149/155 (0.961) |

All intervals below are 95%; recall uses Wilson intervals and paired deltas/MRR use the deterministic
10,000-sample percentile bootstrap.

| Metric | Flat | Flat CI | Parent | Parent CI | Delta | Paired delta CI |
|---|---:|:---:|---:|:---:|---:|:---:|
| Recall@1 | 0.600 | [0.521, 0.674] | 0.619 | [0.541, 0.692] | +0.019 | [-0.045, 0.084] |
| Recall@5 | 0.877 | [0.816, 0.920] | 0.858 | [0.794, 0.904] | -0.019 | [-0.052, 0.006] |
| Recall@10 | 0.897 | [0.839, 0.935] | 0.884 | [0.824, 0.925] | -0.013 | [-0.045, 0.019] |
| Recall@40 | 0.961 | [0.918, 0.982] | 0.961 | [0.918, 0.982] | 0.000 | [0.000, 0.000] |
| MRR@10 | 0.718 | [0.659, 0.776] | 0.710 | [0.650, 0.771] | -0.008 | [-0.049, 0.031] |

Conditional on the 149 mapped cases, Recall@10 changed from 0.933 to 0.919, Recall@40 remained
1.000 and MRR@10 changed from 0.747 to 0.739.

## Recall@10 slices

| Slice | n | Flat | Parent | Delta | Paired delta CI |
|---|---:|---:|---:|---:|:---:|
| language:crossLanguage | 39 | 0.897 | 0.872 | -0.026 | [-0.077, 0.000] |
| language:en | 47 | 0.894 | 0.915 | +0.021 | [0.000, 0.064] |
| language:zh | 69 | 0.899 | 0.870 | -0.029 | [-0.087, 0.029] |
| category:multi_evidence | 20 | 0.850 | 0.800 | -0.050 | [-0.150, 0.000] |
| category:table | 25 | 0.960 | 0.960 | 0.000 | [0.000, 0.000] |
| category:text | 110 | 0.891 | 0.882 | -0.009 | [-0.045, 0.027] |
| primaryCategory:exactLookup | 35 | 0.914 | 0.943 | +0.029 | [0.000, 0.086] |
| primaryCategory:failure | 29 | 1.000 | 0.931 | -0.069 | [-0.172, 0.000] |
| primaryCategory:retrievalDecision | 28 | 0.786 | 0.750 | -0.036 | [-0.107, 0.000] |
| primaryCategory:versionAndAuthorization | 18 | 0.889 | 0.944 | +0.056 | [0.000, 0.167] |

No major slice has a paired 95% interval strictly below zero, but the candidate shows no broad gain
and has nominal regressions in multi-evidence, failure and retrieval-decision scenarios that matter to
the draw.io agent.

## Feasibility and decision

The initially preregistered direct existing-parent mode reached 512 local-tokenizer tokens and was
rejected by Pinecone with HTTP 400 under `truncate=NONE` before upsert or scoring. E2 was amended,
before observing candidate quality, to cap usable parent contexts at 500 tokens with leaf fallback.
Interrupted and rate-limited attempts were cleaned by exact run prefix. The final successful flat and
candidate runs each deleted all 533 vectors.

**Decision: do not promote parent-context-500.** Its primary Recall@10 delta is -0.013, below the
required +0.02, while Recall@40 is unchanged and MRR@10 is slightly lower. Keep the existing flat
leaf retrieval representation and proceed to E3 dense + lexical hybrid recall.
