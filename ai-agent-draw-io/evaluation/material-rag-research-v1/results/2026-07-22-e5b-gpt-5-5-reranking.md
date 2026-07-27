# E5b — GPT-5.5 listwise reranking · Development

- Run commit: `95335ec8`; Development `core-v1`, 155 dense-eligible cases.
- GPT-5.5 requires `max_completion_tokens` and default temperature, so this is a separately recorded candidate from E5 DeepSeek.
- Both arms share the same E1 canonicalization, flat chunks, original query, dense top-80 and top-40 membership.

| Metric | Dense | GPT-5.5 | Delta |
| --- | ---: | ---: | ---: |
| Recall@10 | 0.8968 | 0.8968 | 0.0000 |
| Recall@40 | 0.9613 | 0.9613 | 0.0000 |
| MRR@10 | 0.7160 | 0.7289 | +0.0129 |

GPT-5.5 returned accepted JSON for 21/155 calls (13.55%), below the preregistered 95% availability gate. Its MRR improvement does not substitute for the required Recall@10 gain of +0.02. The paired result is comparable, all n>=20 Recall@10 slices are unchanged, and exact-prefix cleanup returned 0 residual synthetic vectors.

**Decision:** do not promote the GPT-5.5 reranker; do not run Validation; Holdout remains sealed.
