# V1.7 Memory Context selection holdout result

## Verdict

The first frozen-holdout run **did not pass** the release gate. Semantic selection improved
recall over the deterministic SQL baseline, but the final bounded context was not relevant enough
to enable `AUTO_MEMORY_CONTEXT_SEMANTIC_ENABLED` by default.

## Contamination controls

- Holdout: `auto-memory-context-v1`, committed before its first live run in `74a24c44`.
- SHA-256: `18da2d6db1cf1bc7827c03c6f15260ec0b2e7b8d742cea5edcd2baf2bf10f64a`.
- Policy: frozen holdout, not available for tuning or threshold adjustment.
- Inputs: 48 synthetic Memory items and 12 independently labelled Chinese/English queries.
- Provider: Pinecone `multilingual-e5-large`, 1,024 dimensions.
- Persistence: no MySQL or business-store writes and no DeepSeek calls.
- Isolation: vectors were written only to a run-specific `eval` namespace and the test's
  `finally` block deleted all vector IDs; `waitUntilDeleted` confirmed that fetch returned none.
- The holdout, gates and V1.7 selector were not changed after observing this result.

## Metrics

The selector used the released V1.7 limits: at most 8 items per scope, 12 items in total and
6,000 rendered characters.

| Metric | SQL baseline | Semantic V1.7 | Pre-registered gate |
|---|---:|---:|---:|
| Recall@1 | 0.0000 | 0.0833 (1/12) | >= 0.65 |
| Recall@3 | 0.0000 | 0.1667 (2/12) | >= 0.85 |
| Recall@12 | 0.0000 | 0.6667 (8/12) | >= 0.95 |
| Mean reciprocal rank | 0.0000 | 0.1933 | >= 0.75 |
| Precision@3 | 0.0000 | 0.0556 | observed only |
| Irrelevant selection rate | 1.0000 | 0.9333 | observed only |
| Mean selected count | 10.0 | 10.0 | observed only |
| Forbidden selection rate | 0.0000 | 0.0000 | 0.0 |
| Unauthorized selection count | 0 | 0 | 0 |
| Recall@3 lift over SQL | — | +0.1667 | >= 0.50 |

The mean selection count is 10 because USER-only cases are capped at 8 while Chartbook cases can
use the total budget of 12.

## Per-case first relevant rank

`miss` means the expected Memory did not survive the final V1.7 selection budget.

| Case | SQL | Semantic |
|---|---:|---:|
| Chinese global concise labels | miss | 3 |
| English global muted palette | miss | miss |
| Chinese global orthogonal routing | miss | miss |
| English global avoid icons | miss | 5 |
| Chinese global Inter font | miss | miss |
| English global rounded corners | miss | 7 |
| Chinese Chartbook dashed dependencies | miss | miss |
| English Chartbook top-down layout | miss | 4 |
| Chinese Chartbook risk color | miss | 12 |
| English Chartbook database cylinders | miss | 1 |
| Chinese Chartbook team swimlanes | miss | 7 |
| English Chartbook detailed-label override | miss | 6 |

The detailed-label case correctly excluded the same-key USER Memory, so the Chartbook override
contract passed even though its relevant Memory ranked sixth.

## Interpretation

1. Semantic retrieval is directionally useful: SQL found none of the deliberately old targets,
   while semantic V1.7 found eight within the final context.
2. It is not yet good enough for release: only two targets were in the top three and four targets
   did not appear in the final bounded context. This report does not claim whether those four were
   below the final budget or absent from the provider's raw top 16.
3. One likely architectural contributor is that V1.7 has no relevance-score boundary. It accepts
   the provider order, then fills remaining slots from SQL. With a single relevant label per case,
   the released behavior produced a 93.33% irrelevant selection rate and would spend Prompt budget
   on weakly related Memory.
4. Scope and conflict safety held: no unauthorized item was selected and the same-key USER value
   did not bypass the Chartbook override.
5. This evaluation measures Memory selection, not final answer adherence. No generation model was
   called, so it must not be presented as an end-user answer-quality score.

## Next evaluation boundary

Do not tune against this holdout. Diagnose raw vector rank and score distributions using a separate
development cohort, then evaluate one small architectural change at a time (for example a
score-aware cutoff or a bounded reranker). A new untouched holdout version is required before any
future release claim.
