# E3 dense vs projection-backed hybrid on Development

Date: 2026-07-22. Status: **comparable; candidate not promoted**. Validation and Holdout remained sealed.

## Scope and frozen controls

- 155 answerable Development cases whose required Evidence is text/table/multi-evidence; 149/155 have a fixed
  gold-to-child mapping.
- Commit `df6b9f46`, E1 `canonical-v5`, `flat-leaf-v1`, 533 chunks, integrated
  `multilingual-e5-large`, production tokenizer, and top 40.
- The only decision variable is `dense-v1` vs `hybrid-projection-rrf-v1`. The hybrid lexical lane uses the
  persisted `LexicalProjection` signals with deterministic TF-IDF, CJK bigrams and the product's boolean +2 exact
  boost, then the product RRF parameters `lexical=1.2`, `dense=1.0`, `k=60`.
- This projection-backed ranker is not a score-level reproduction of MySQL `NATURAL LANGUAGE MODE`; a promoted
  candidate would still need an online MySQL check.
- Run lock: `2026-07-22-e3-run-corpus-lock.json`, SHA-256
  `e01f2a4307e3c22fae8f2156157c593f9b5851bf941a44893b2c0f02f90076bc`.

The first attempt used two independent Pinecone runs. Before confidence intervals were calculated, the comparator
found near-score swaps in the dense lane and rejected the pair. The final runner therefore emitted both modes from
one passage embedding, query embedding and Pinecone query. All 155 dense lanes, lexical lanes and fixed gold maps
are identical across the final raw files. The rejected pair is not used below. Every run deleted its 533 vectors.

## Paired result

| Metric | Dense | Hybrid | Paired delta | Delta 95% CI |
|---|---:|---:|---:|:---:|
| Recall@1 | 0.600 | 0.613 | +0.013 | [-0.052, 0.077] |
| Recall@5 | 0.877 | 0.871 | -0.006 | [-0.045, 0.032] |
| Recall@10 | 0.890 | 0.897 | **+0.006** | [-0.019, 0.032] |
| Recall@40 | 0.961 | 0.961 | 0.000 | [0.000, 0.000] |
| MRR@10 | 0.718 | 0.723 | +0.004 | [-0.034, 0.042] |

Mapping is unchanged at 149/155 (0.961). Conditional-on-mapping Recall@10 is 0.926→0.933 and MRR@10 is
0.747→0.752. None of the aggregate improvements excludes zero, and the primary Recall@10 gain is below the
pre-registered +0.02 promotion threshold.

## Slice and case diagnosis

- Multi-evidence Recall@10 improves 0.850→0.950 (delta +0.100, paired CI [0.000, 0.250]). Cases
  `controlled-591` and `controlled-608` cross into the top 10.
- Version/authorization improves 0.889→0.944 (n=18, below the pre-registered major-slice size of 20);
  `controlled-532` moves from rank 12 to 2.
- Exact lookup falls 0.914→0.857 (delta -0.057, paired CI [-0.143, 0.000]). `controlled-050` and
  `controlled-053` fall from ranks 3/2 to rank 22. Their questions ask for an unknown identifier/date rather than
  containing the answer term, so broad lexical overlap promotes distractors and RRF pushes the strong dense-only
  gold result down.
- English falls by 0.021 and Chinese improves by 0.029; both paired intervals include zero. Text overall falls by
  0.009. The lexical lane is non-empty for 129/155 cases, with median 9 and mean 11.25 candidates.

## Decision

Do **not** promote `hybrid-projection-rrf-v1`, and do not open Validation. It misses the +0.02 primary threshold,
does not improve candidate Recall@40, and trades multi-evidence gains for harmful exact-lookup reordering. Keep
`dense-v1` as the frozen retrieval baseline. The next E3 experiment should change one variable only: preregister a
query-rewrite candidate while leaving dense retrieval and flat chunks fixed. Any later lexical candidate should use
query-intent gating or reranking rather than applying the 1.2 lexical lane to every question.

Raw paired outputs are `2026-07-22-e3-dense-development-raw.json` and
`2026-07-22-e3-hybrid-development-raw.json`; deterministic intervals and slices are in
`2026-07-22-e3-development-comparison.json`.
