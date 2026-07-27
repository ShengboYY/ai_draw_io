# E4 evidence deduplication vs ranked raw

Date: 2026-07-22. Status: **Development gate passed, Validation effect did not replicate; candidate not promoted**.
Holdout remained sealed.

## Scope and frozen controls

- Baseline: `ranked-raw-v1`; candidate: `evidence-dedup-v1` with fingerprint
  `evidence-dedup-v1:text-sha-or-evidence-jaccard-0.8:citable-first`.
- Both modes use commit `068d7550`, E1 `canonical-v5`, `flat-leaf-v1`, `dense-v1`, original queries,
  533 chunks, `multilingual-e5-large`, the production tokenizer, fixed gold children and the same source filters.
- Both outputs are derived from the same per-case Pinecone top-80 pool. The baseline takes the first 40. The
  candidate collapses chunks with identical retrieval-text SHA or source-backed Evidence-ID Jaccard at least 0.8,
  prefers a citable chunk within a duplicate family and backfills from deeper unique candidates where available.
- Run lock: `2026-07-22-e4-run-corpus-lock.json`, SHA-256
  `9626abed4bed73ff20545fe5c0eb817d8351d2dd1aa83592ba1fa23db53d4371`.
- The archived case results include the exact top-80 pool, and the comparator rejects any pool drift between arms.
- All 533 temporary vectors were deleted after each completed paired run. One earlier Validation attempt failed DNS
  before upsert; a later visibility-stalled attempt was interrupted and all 533 vectors were removed by exact run
  prefix. Neither attempt was scored. Only the successful clean rerun is included below.

## Paired quality result

| Split | Mode | R@1 | R@5 | R@10 | R@40 | MRR@10 |
|---|---|---:|---:|---:|---:|---:|
| Development | ranked raw | 0.587 | 0.877 | 0.897 | 0.961 | 0.713 |
| Development | evidence dedup | 0.587 | 0.877 | 0.897 | 0.961 | 0.713 |
| Validation | ranked raw | 0.534 | 0.740 | 0.822 | 0.836 | 0.637 |
| Validation | evidence dedup | 0.534 | 0.740 | 0.822 | 0.836 | 0.637 |

Every paired aggregate delta is exactly 0.000 with delta 95% CI `[0.000, 0.000]`. Every recorded language,
content-category and primary-category Recall@10 slice is also unchanged. The candidate therefore satisfies the
quality non-regression conditions, but the absolute Validation dense baseline still remains below the plan's
retrieval gates; deduplication does not address those pre-existing misses.

## Did deduplication materially activate?

| Split | Changed cases | Baseline positions | Removed/replaced duplicate positions | Activation rate | Top-10 removals |
|---|---:|---:|---:|---:|---:|
| Development | 46/155 | 4,121 | 112 | **2.72%** | 10 |
| Validation | 4/73 | 1,575 | 4 | **0.25%** | 0 |

Development exceeds the preregistered 2% activation threshold and therefore qualified for Validation. Validation
does not reproduce that incidence: only `controlled-225` through `controlled-228` change, one position each, all
below the top 10. Some Development sources contain fewer than 40 unique candidates after collapse, so their final
lists are shorter rather than padded with duplicate evidence; this is intentional and included in the activation
count.

## Product-boundary limitation

All authored cases have one allowed source version, and every evaluated candidate pool is single-source. This
experiment can only evaluate duplicate evidence within one mounted material. It provides no evidence about source
quotas, conflicting versions or diversity across several chartbook-mounted materials. Those are central draw.io
agent cases and require a dedicated multi-source E4 fixture before making a source-diversity claim.

## Decision

Do **not** promote `evidence-dedup-v1` as the default postprocessor. It is quality-neutral, but its Development
activation rate does not generalize to Validation, so the measured benefit is too sparse to justify a product
change. Keep `ranked-raw-v1`, original dense retrieval and flat chunks frozen. E4 remains open: next create a
multi-source, chartbook-mounted evaluation set containing overlapping, complementary, superseded and unauthorized
materials, preregister source-diversity metrics, then compare raw ranking with a dedup/diversity candidate. Holdout
must remain sealed.

Raw outputs are `2026-07-22-e4-ranked-raw-development-raw.json`,
`2026-07-22-e4-evidence-dedup-development-raw.json`, `2026-07-22-e4-ranked-raw-validation-raw.json` and
`2026-07-22-e4-evidence-dedup-validation-raw.json`. Paired statistics are in
`2026-07-22-e4-development-comparison.json` and `2026-07-22-e4-validation-comparison.json`.
