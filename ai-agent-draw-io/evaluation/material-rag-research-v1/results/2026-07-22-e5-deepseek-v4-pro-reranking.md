# E5 — DeepSeek V4 Pro listwise reranking · Development

- Date: 2026-07-22
- Run commit: `55d1d756`
- Split/profile: Development / `core-v1`, 155 answerable dense-eligible cases
- Baseline/candidate: `none-v1` / `llm-listwise-v1`
- Fixed controls: E1 `e1-v5`, `flat-leaf-v1`, original query, dense retrieval, ranked raw, same Pinecone top-80 and reranked top-40 only
- Candidate: `deepseek-v4-pro`, `thinking=disabled`, `temperature=0`, short opaque IDs `c01`–`c40`, 800 characters per candidate
- Run lock: `2026-07-22-e5-run-corpus-lock.json`, SHA-256 `bb98c24cd56834dcbdc69a201c4657ab400ef61e15b8a0475876f7b688b1b8ef`

## Result

The paired comparison is comparable: both arms have the same 155 cases, 533 indexed chunks and identical per-case top-80 retrieval pools. The candidate returned accepted JSON for only **5/155 (3.23%)** calls, below the preregistered 95% minimum. Invalid or empty model outputs safely retained the original dense order.

| Metric | Dense baseline | DeepSeek rerank | Delta |
| --- | ---: | ---: | ---: |
| Recall@10 | 0.8968 | 0.8968 | 0.0000 |
| Recall@40 | 0.9613 | 0.9613 | 0.0000 |
| MRR@10 | 0.7160 | 0.7209 | +0.0048 |

The MRR 95% paired delta interval is `[0.0000, 0.0145]`; all major Recall@10 slices with `n >= 20` are unchanged. There is no quality regression, but neither the JSON availability gate nor the required Recall@10 gain of `+0.02` is met.

## Operational record and decision

- 155 calls; 603,561 prompt tokens and 14,558 completion tokens; total model latency 357,625 ms.
- The first attempted run exposed an empty final-content response when thinking was enabled. It produced no formal raw result, its exact Pinecone prefix cleanup returned 0, and the rerun fixed `thinking=disabled` before the measured run.
- The measured runner deleted vectors in its `finally` block; a second exact-prefix cleanup returned 0.
- **Decision: do not promote `llm-listwise-v1`; do not run Validation; Holdout remains sealed.** The next experiment is E6 context selection, or a separately preregistered reranker protocol with a provider/model that meets the JSON-availability gate.

Artifacts: `2026-07-22-e5-dense-development-raw.json`, `2026-07-22-e5-deepseek-v4-pro-development-raw.json`, and `2026-07-22-e5-deepseek-v4-pro-development-comparison.json`.
