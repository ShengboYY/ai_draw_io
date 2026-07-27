# E2 Development: flat leaf vs parent-context-500

E2 used **155** answerable dense-eligible Development cases. Holdout and Validation remained sealed.
Both runs used commit `8c0ba9a5`, E1 canonical mode `e1-v5`, the same 533 child IDs,
`multilingual-e5-large`, top 40 and corpus-lock snapshot
`2026-07-22-e2-run-corpus-lock.json` (SHA-256 `c9c00808…`).

The flat baseline embedded each child `retrievalText`. The candidate embedded the child's existing
same-page, same-section neighbor context when it was at most 500 local-tokenizer tokens, otherwise it
fell back to the child text. Gold child IDs were derived once from the fixed child projection, saved
per anchor in both raw files and required to match exactly before comparison; parent text could not
expand the relevant set.

| Profile | Flat | Parent-context-500 |
|---|---:|---:|
| Searchable chunks | 533 | 533 |
| Embedding tokens p50 / p95 / max | 105 / 356 / 419 | 272 / 383 / 495 |
| Gold mapping | 149/155 (0.961) | 149/155 (0.961) |

All intervals below are 95%; recall uses Wilson intervals and paired deltas/MRR use the deterministic
10,000-sample percentile bootstrap.

| Metric | Flat | Flat CI | Parent | Parent CI | Delta | Paired delta CI |
|---|---:|:---:|---:|:---:|---:|:---:|
| Recall@1 | 0.574 | [0.495, 0.649] | 0.490 | [0.413, 0.568] | -0.084 | [-0.161, -0.006] |
| Recall@5 | 0.852 | [0.787, 0.899] | 0.832 | [0.766, 0.883] | -0.019 | [-0.065, 0.026] |
| Recall@10 | 0.877 | [0.816, 0.920] | 0.877 | [0.816, 0.920] | 0.000 | [-0.045, 0.045] |
| Recall@40 | 0.935 | [0.885, 0.965] | 0.961 | [0.918, 0.982] | +0.026 | [0.006, 0.052] |
| MRR@10 | 0.695 | [0.635, 0.754] | 0.633 | [0.571, 0.694] | -0.062 | [-0.114, -0.009] |

Conditional on the 149 mapped cases, Recall@10 remained 0.913, Recall@40 changed from 0.973 to
1.000 and MRR@10 changed from 0.723 to 0.658.

## Recall@10 slices

| Slice | n | Flat | Parent | Delta | Paired delta CI |
|---|---:|---:|---:|---:|:---:|
| language:crossLanguage | 39 | 0.897 | 0.872 | -0.026 | [-0.128, 0.051] |
| language:en | 47 | 0.872 | 0.894 | +0.021 | [-0.043, 0.106] |
| language:zh | 69 | 0.870 | 0.870 | 0.000 | [-0.072, 0.072] |
| category:multi_evidence | 20 | 0.850 | 0.800 | -0.050 | [-0.150, 0.000] |
| category:table | 25 | 0.920 | 0.920 | 0.000 | [-0.120, 0.120] |
| category:text | 110 | 0.873 | 0.882 | +0.009 | [-0.045, 0.064] |
| primaryCategory:exactLookup | 35 | 0.914 | 0.943 | +0.029 | [0.000, 0.086] |
| primaryCategory:failure | 29 | 0.966 | 0.897 | -0.069 | [-0.207, 0.069] |
| primaryCategory:retrievalDecision | 28 | 0.714 | 0.750 | +0.036 | [-0.071, 0.143] |
| primaryCategory:versionAndAuthorization | 18 | 0.889 | 0.944 | +0.056 | [0.000, 0.167] |

No major Recall@10 slice has a paired 95% interval strictly below zero. However, aggregate Recall@1
and MRR@10 both regress significantly, showing that longer parent text moves relevant child evidence
down the ranking even though more of it survives to top 40.

## Feasibility and decision

The initially preregistered direct existing-parent mode reached 512 local-tokenizer tokens and was
rejected by Pinecone with HTTP 400 under `truncate=NONE` before upsert or scoring. E2 was amended,
before observing candidate quality, to cap usable parent contexts at 500 tokens with leaf fallback.
Interrupted and rate-limited attempts were cleaned by exact run prefix. The final successful flat and
candidate runs each deleted all 533 vectors.

**Decision: do not promote parent-context-500.** Its primary Recall@10 delta is 0.000, below the
required +0.02. Recall@40 improves by 0.026, but Recall@1 falls by 0.084 and MRR@10 falls by 0.062;
both regression intervals exclude zero. Keep the existing flat leaf retrieval representation and
proceed to E3 dense + lexical hybrid recall.
