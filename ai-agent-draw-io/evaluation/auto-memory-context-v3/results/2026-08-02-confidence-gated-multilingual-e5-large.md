# V1.7.2 confidence-gated Memory Context evaluation

## Verdict

The first and only frozen V3 holdout run **did not pass** its pre-registered release gate. Query-level
rejection generalized well: seven of eight positive targets were selected at rank 1 and all eight
negative operations returned no Memory. Candidate-level inclusion remained too broad, producing
three related but non-labelled selections and a 30% irrelevant selection rate against a 25% limit.
Semantic Memory Context remains disabled.

## Isolation and contamination controls

- Development-v2 and V3 were frozen in `22ce4202`; the policy and pre-registration were frozen in
  `2614f4d6` before the first V3 request.
- Development-v2 SHA-256:
  `48f55a6830993e7474da10f11b461dae7d8e64fe3ee8efa5f3d409f107185eb5`.
- V3 SHA-256:
  `e19cb9a885132e1bdcb3ee41c1cf3c5409154f6b00df866a9ce3d838dcb0bd64`.
- V1/V2 were not reused for parameter selection. V3 was run once and was not used to alter the
  policy, gate or labels.
- All 43 Memory records and 16 queries were synthetic. There were zero DeepSeek calls and zero
  MySQL/business-store writes.
- The run used an isolated Pinecone namespace containing `eval`. The evaluator deleted all vector
  IDs in `finally` and waited until none remained.

## Frozen policy

The top hit must score at least `0.82`. If the second hit also reaches `0.82`, every hit at or above
that floor is retained. Otherwise, the top hit must lead the second by at least `0.02`. MySQL
authority, Chartbook same-key override, budgets and SQL technical-failure fallback were unchanged.

## V3 result

| Metric | SQL baseline | V1.7.2 | Gate | Result |
|---|---:|---:|---:|---|
| Recall@1 | 0.0% | 87.5% (7/8) | >= 75.0% | pass |
| Recall@3 | 0.0% | 87.5% (7/8) | >= 87.5% | pass |
| Recall@12 | 0.0% | 87.5% (7/8) | >= 87.5% | pass |
| MRR | 0.0000 | 0.8750 | >= 0.8000 | pass |
| Recall@3 lift over SQL | — | 87.5 points | >= 75.0 points | pass |
| Precision@3 | 0.0% | 70.0% | observed | — |
| Irrelevant selection rate | 100.0% | 30.0% (3/10) | <= 25.0% | **fail** |
| Negative-case selection rate | 100.0% | 0.0% (0/8) | <= 25.0% | pass |
| Mean selected count | 10.0 | 0.625 | observed | — |
| Forbidden selection rate | 0.0% | 0.0% | 0.0% | pass |
| Unauthorized selection count | 0 | 0 | 0 | pass |

The only missed target was the resilience-zone layout Memory at `0.819682062`, immediately below
the frozen floor. No threshold adjustment was made.

Three positive cases retained an additional high-scoring but non-labelled candidate:

| Case | Correct selection | Additional selection | Additional score |
|---|---|---|---:|
| Database label position | database label below icon | database fill color | 0.827123582 |
| Environment in title | deployment environment title | group heading case | 0.821819842 |
| Secret-store badge | Chartbook lock badge | certificate-store ribbon badge | 0.841424584 |

In the secret-store case, the same-key USER key badge scored `0.864161491`; the existing Chartbook
override correctly removed it. The unrelated certificate badge remained because it independently
cleared the absolute floor.

## Interpretation and next boundary

- Query-level abstention is supported: all operational negative cases returned empty, including a
  negative whose top score (`0.823401809`) exceeded the score floor but lacked the required lead.
- The remaining problem is candidate-level breadth after a query is accepted. Treating every hit
  above the same absolute floor as equally injectable conflates “this query has relevant Memory”
  with “all high-score candidates are relevant.”
- V3 cannot be used to choose a top-relative window or another cutoff. Any next experiment needs a
  new development cohort and a new untouched V4 holdout, with query acceptance and candidate
  inclusion measured separately.
- No reranker or extra DeepSeek call is justified yet. A bounded deterministic inclusion rule should
  be evaluated first. The semantic feature flag remains off until an independent holdout passes.
