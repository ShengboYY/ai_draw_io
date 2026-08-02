# Auto Memory Context V1.8.2 pre-registration

## Change under evaluation

V1.8.2 keeps MySQL authority, scope override, Prompt budget and the existing single-query
`0.82 / 0.02 / 0.03` policy. It changes only planned multi-intent recall:

1. A zero/failed planner result may fall back to two or three clauses only when the request contains
   a semicolon, full-width semicolon or newline.
2. Each planned facet may collect at most four candidates above a separate candidate-only floor.
3. MySQL revalidates the pooled identities once; each facet contributes at most its first valid
   candidate to final selection.
4. The original request runs through the existing strict policy only when no facet produced a
   candidate. No additional model call or Memory persistence field is added.

`AUTO_MEMORY_CONTEXT_SEMANTIC_ENABLED` and `AUTO_MEMORY_CONTEXT_MULTI_INTENT_ENABLED` remain
default-off regardless of synthetic results.

## Frozen protocol

- Development: `auto-memory-context-e2e-development-v2/cohort.json`
- One-shot holdout: `auto-memory-context-e2e-v2/holdout.json`
- Development SHA-256: `d3da68283e401122b7abad62eab81d80e476f632ad1ec1e12bf3ba4e24066702`
- Holdout SHA-256: `e46fecf27bb2efdfce806e8f90c1614156441bfe7b5bad364c27fa9dcbc5f915`
- The datasets use different Memory targets and wording from the retired V1 holdout.
- Both include multi-intent, strong delimiters, bilingual requests, coordinated single properties,
  negative operations, USER/CHARTBOOK override, disabled/unauthorized duplicates and SQL-window noise.
- Dataset hashes are recorded before implementation.

## Gate

| Metric | Required |
|---|---:|
| Raw candidate Recall@4 | >= 90% |
| Target recall in final Prompt | >= 80% |
| Complete positive Case rate | >= 75% |
| Prompt precision | >= 85% |
| Irrelevant Prompt injection | <= 15% |
| Negative Case injection | <= 25% |
| Forbidden / unauthorized / disabled selection | 0 |
| Selected-to-Prompt parity | 100% |

The development cohort may guide only the generic facet policy. After implementation and development
results are committed, the holdout runs once. A failed holdout is recorded without tuning or rerun.
