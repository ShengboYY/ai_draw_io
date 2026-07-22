# E3 evidence-focused query rewrite vs original query on Development

Date: 2026-07-22. Status: **comparable; candidate not promoted**. Validation and Holdout remained sealed.

## Scope and frozen controls

- 155 answerable Development cases with text/table/multi-evidence requirements; 149/155 have a fixed gold child.
- Commit `cf22d679`, E1 `canonical-v5`, `flat-leaf-v1`, `dense-v1`, 533 chunks,
  `multilingual-e5-large`, production tokenizer, source-version filter and top 40.
- The only variable is query text: `original-v1` vs `evidence-focused-v1`. The candidate detects whether the query
  contains Han characters and prepends a same-language instruction to find the source passage containing the facts,
  rules, values or steps that directly answer the request.
- The rewrite does not read gold anchors, expected answers, case categories or document language. It does not add
  lexical retrieval, RRF, decomposition or target labels.
- Both modes share one passage embedding/upsert. Run lock:
  `2026-07-22-e3-query-rewrite-run-corpus-lock.json`, SHA-256
  `1e391d0337f2d817d70a44acbde1e1e9700a0559097d050ec34862b09a33d020`.
- All 533 temporary vectors were deleted after the paired run.

## Paired result

| Metric | Original | Evidence-focused | Paired delta | Delta 95% CI |
|---|---:|---:|---:|:---:|
| Recall@1 | 0.600 | 0.548 | **-0.052** | [-0.097, -0.006] |
| Recall@5 | 0.877 | 0.826 | **-0.052** | [-0.097, -0.013] |
| Recall@10 | 0.890 | 0.871 | -0.019 | [-0.052, 0.013] |
| Recall@40 | 0.961 | 0.961 | 0.000 | [0.000, 0.000] |
| MRR@10 | 0.720 | 0.671 | **-0.049** | [-0.082, -0.017] |

Mapping is unchanged at 149/155. Conditional-on-mapping Recall@10 falls 0.926→0.906 and MRR@10 falls
0.749→0.698. Recall@1, Recall@5 and MRR@10 regress significantly; the candidate therefore fails independently
of the +0.02 promotion threshold.

## Slice and case diagnosis

- Retrieval-decision Recall@10 improves 0.750→0.786; `controlled-139` moves from rank 11 to 4. This is the
  intended effect of asking explicitly for a source rule.
- Chinese Recall@10 falls 0.899→0.855, while English is unchanged at 0.894. The long generic Chinese prefix
  dominates more of the query embedding than it does for the English slice.
- Table falls 0.960→0.920, failure 1.000→0.931, exact lookup 0.914→0.886 and version/authorization
  0.889→0.833. `controlled-445`, `controlled-446` and `controlled-515` cross out of the top 10.
- Multi-evidence is unchanged in aggregate, but the candidate exchanges one gain (`controlled-593`, rank 15→10)
  for one loss (`controlled-586`, rank 10→14); `controlled-608` falls from rank 15 to 38.

## Decision

Do **not** promote `evidence-focused-v1` and do not open Validation. A generic evidence instruction helps one
retrieval-policy family but dilutes the concrete terms needed for early ranking, especially in Chinese and failure
queries. Keep `original-v1`, `dense-v1` and flat chunks as the frozen baseline. E3 has now tested its predefined
hybrid and query-rewrite candidates without a qualifying gain; proceed to E4 deduplication/source-diversity as the
next single-variable stage. Any future query rewriting should be intent-specific and independently preregistered,
not a universal prefix.

Raw results are `2026-07-22-e3-original-query-development-raw.json` and
`2026-07-22-e3-evidence-focused-development-raw.json`; paired intervals and slices are in
`2026-07-22-e3-query-rewrite-development-comparison.json`.
