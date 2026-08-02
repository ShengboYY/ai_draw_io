# V1.7.1 score-aware Memory Context evaluation

## Verdict

V1.7.1 produced a large improvement, but the first frozen V2 holdout **did not pass** its
pre-registered release gate. The score ordering fix is supported by both cohorts; a single absolute
score cutoff is not stable enough across them. Semantic context therefore remains disabled.

## Isolation and contamination controls

- The development cohort and V2 holdout were committed before implementation in `9c20d067`.
- Development SHA-256:
  `fd6ed4486e5bbc881f28753682672c96e305fa0c6806b8c418ecf9540f7051bc`.
- V2 holdout SHA-256:
  `1d3b28b99f28209f3f63bdcc329825f48add7b69e17b38b9adf00efe78516ce6`.
- The two cohorts use different target topics and distractor banks.
- Only the development cohort was used to select the `0.827` minimum score.
- V2 was run once. Its data, gate, implementation and score were not changed after the result.
- All inputs were synthetic. There were no DeepSeek calls and no MySQL/business-store writes.
- Each run used a separate Pinecone `eval` namespace. The evaluator deleted every vector ID in a
  `finally` block and waited until fetch returned none.

## Implemented experiment

1. Pinecone query matches now retain their finite provider score.
2. The Memory adapter explicitly sorts matches by descending score instead of trusting response
   order. The first development run showed response order was not score order.
3. A successful semantic query keeps only hits at or above `0.827`; it may inject zero items and
   no longer pads successful results with SQL Memory.
4. A technical vector failure still returns the deterministic SQL baseline.
5. MySQL owner/scope/status validation, Chartbook same-key override, read-set pinning and existing
   entry/character budgets remain unchanged.

## Development cohort

The initial score-observation run used no cutoff. After explicit score sorting and the development-
only cutoff, the same development cohort changed as follows:

| Metric | Before score-aware selection | After (`0.827`) |
|---|---:|---:|
| Recall@1 | 12.50% | 100.00% |
| Recall@3 | 50.00% | 100.00% |
| Recall@12 | 87.50% | 100.00% |
| MRR | 0.3658 | 1.0000 |
| Irrelevant selection rate | 90.67% | 46.67% |
| Mean selected count | 9.375 | 1.875 |

All eight development targets had the highest provider score. Their minimum target score was
`0.827321470`; `0.827` was selected before opening V2.

## Frozen V2 holdout

| Metric | SQL baseline | V1.7.1 | Pre-registered gate |
|---|---:|---:|---:|
| Recall@1 | 0.00% | 75.00% (6/8) | >= 50.00% |
| Recall@3 | 0.00% | 75.00% (6/8) | >= 75.00% |
| Recall@12 | 0.00% | 75.00% (6/8) | >= 87.50% |
| MRR | 0.0000 | 0.7500 | >= 0.6500 |
| Precision@3 | 0.00% | 60.00% | observed only |
| Irrelevant selection rate | 100.00% | 40.00% | <= 50.00% |
| Mean selected count | 10.0 | 1.25 | observed only |
| Forbidden selection rate | 0.00% | 0.00% | 0.00% |
| Unauthorized selection count | 0 | 0 | 0 |

The only failed gate was Recall@12. All eight correct targets were raw rank 1 after score sorting,
but two were rejected by the development cutoff:

| Case | Target score | Next score | Selected |
|---|---:|---:|---:|
| Uppercase acronyms | 0.821102560 | 0.813066125 | no |
| Monochrome printing | 0.848142684 | 0.824008524 | yes |
| Thin connectors | 0.855989000 | 0.823778749 | yes |
| Legend on right | 0.827048957 | 0.824952126 | yes |
| API pill nodes | 0.906071000 | 0.867336214 | yes |
| Orange batch jobs | 0.823589385 | 0.808619738 | no |
| Dotted optional dependencies | 0.902849853 | 0.841236472 | yes |
| Purple authentication boundary | 0.844581902 | 0.842364252 | yes |

The authentication case also returned the same-key USER value at `0.842364252`; the Chartbook
override correctly removed it from the final context.

## Interpretation and next boundary

- Explicit score sorting corrected the dominant ranking problem: raw Top-1 accuracy was 8/8 on
  both development and V2.
- Sparse selection materially reduced prompt pollution and did not weaken tenant/scope safety.
- The absolute `0.827` cutoff overfit the development score range. Lowering it after seeing V2
  would contaminate V2, so no adjustment or rerun was made.
- The implementation remains useful behind the existing disabled feature flag, but it is not a
  release-quality semantic policy yet.
- Any next experiment must use a new development cohort to design an adaptive rejection rule, then
  a new untouched V3 holdout. V2 must not be reused for tuning or a release claim.
