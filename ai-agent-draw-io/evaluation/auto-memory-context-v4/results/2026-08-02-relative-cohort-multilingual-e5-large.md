# V1.7.3 relative-cohort Memory Context evaluation

## Verdict

The first and only frozen V4 holdout run **did not pass** its release gate. V1.7.3 solved the scoped
candidate-pollution problem: every selected Memory was relevant, all eight negative operations
returned empty, and scope/override safety passed. Overall recall remained too low at 60%, so semantic
Memory Context stays disabled.

## Isolation and contamination controls

- Development-v3 and V4 were frozen in `a7b36ac0`; implementation and pre-registration were frozen
  in `fd3e1a3b` before the first V4 request.
- Development-v3 SHA-256:
  `5347df5d500c9632c97f34a2f561be18d4808d1e41c71a3916f98274a6b2a900`.
- V4 SHA-256:
  `9f7d173ea029478dea137227e72b6fd30c674792c8d20510913802df0f3fdfab`.
- V1–V3 were not used for V1.7.3 parameter selection. V4 was run once and was not used to alter the
  implementation, gate or labels.
- All 45 Memory records and 16 queries were synthetic. The evaluator made zero DeepSeek calls and
  zero MySQL/business-store writes.
- The run used an isolated Pinecone namespace containing `eval`; all vector IDs were deleted in
  `finally` and verified absent.

## Frozen policy

The V1.7.2 query gate remains `minimumScore=0.82` and `minimumLead=0.02`. After acceptance, V1.7.3
keeps only hits at or above `0.82` and within `0.03` of Top-1. More than four retained near-ties cause
the semantic result to abstain rather than truncate an ambiguous cohort. MySQL authority,
Chartbook same-key override, budgets and SQL technical-failure fallback remain unchanged.

## V4 result

| Metric | SQL baseline | V1.7.3 | Gate | Result |
|---|---:|---:|---:|---|
| Recall@1 | 0.0% | 60.0% (6/10) | >= 60.0% | pass |
| Recall@3 | 0.0% | 60.0% (6/10) | >= 80.0% | **fail** |
| Recall@12 | 0.0% | 60.0% (6/10) | >= 90.0% | **fail** |
| Positive-case hit rate | 0.0% | 75.0% (6/8) | >= 87.5% | **fail** |
| MRR | 0.0000 | 0.7500 | >= 0.7500 | pass |
| Recall@3 lift over SQL | — | 60.0 points | >= 70.0 points | **fail** |
| Precision@3 | 0.0% | 100.0% | observed | — |
| Irrelevant selection rate | 100.0% | 0.0% (0/6) | <= 20.0% | pass |
| Negative-case selection rate | 100.0% | 0.0% (0/8) | <= 25.0% | pass |
| Mean selected count | 10.0 | 0.375 | observed | — |
| Forbidden selection rate | 0.0% | 0.0% | 0.0% | pass |
| Unauthorized selection count | 0 | 0 | 0 | pass |

## Miss analysis

| Case | Raw target position/score | Boundary | Outcome |
|---|---|---|---|
| Burgundy secret service | rank 16 / `0.791318774` | below query floor | single target missed |
| USER dual intent | ranks 1/2; second `0.842677414` | `0.035714627` below Top-1, outside `0.03` | one of two targets retained |
| CHARTBOOK dual intent | ranks 1/2: `0.822061181` / `0.809761047` | second below `0.82`, Top-1 lead below `0.02` | both targets rejected before inclusion |

The Chartbook same-key edge-cache case passed: the scoped star Memory was selected and the USER
hexagon value was excluded. No forbidden or unauthorized Memory was injected.

## Interpretation and next boundary

- The V1.7.3 claim is supported narrowly: relative cohort selection removed candidate pollution on
  an independent holdout without creating negative-case false positives.
- A single embedding for a compound request gives uneven scores to its clauses. Widening the window
  after V4 would trade back the precision just gained and contaminate V4.
- The next experiment should not add more global thresholds. It should compare bounded query-clause
  decomposition or another multi-intent retrieval method on a new development set, followed by a
  new untouched holdout. Cross-language single-target retrieval should be measured separately.
- No extra DeepSeek reranker is justified by this stage. Semantic injection remains off; the SQL
  default and vector technical-failure fallback are unchanged.
