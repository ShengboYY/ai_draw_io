# Auto Memory Context V1.8.2 pre-registration

## Change under evaluation

V1.8.2 keeps MySQL authority, scope override, Prompt budget and the existing single-query
`0.82 / 0.02 / 0.03` policy. It changes only planned multi-intent recall:

1. A zero/failed planner result may fall back to two or three clauses only when the request contains
   a semicolon, full-width semicolon or newline.
2. Each planned facet may collect at most four candidates above a separate candidate-only floor.
3. MySQL revalidates the pooled identities once; each facet contributes at most one valid
   candidate. Different decision keys must have a `0.01` lead, while a same-key Chartbook override
   remains authoritative.
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

## Development result

The locked implementation passed development-v2 with candidate Recall@4 `100%`, final target recall
`80%`, complete-positive Case rate `83.33%`, Prompt precision `88.89%`, irrelevant injection
`11.11%`, negative Case injection `25%`, and selection-to-Prompt parity `100%`. Unauthorized,
disabled and forbidden selections were all zero. The `0.01` facet lead was kept as a round,
conservative boundary instead of tuning a smaller decimal to the two development near-ties.

Full evidence is stored in `development-report.json`.

## One-shot holdout result

After commit `89f1f2e8` locked the implementation and development evidence, holdout-v2 was run once.
It passed with candidate Recall@4 `100%`, final target recall `90%`, complete-positive Case rate
`83.33%`, Prompt precision `90%`, irrelevant injection `10%`, negative Case injection `25%`, and
selection-to-Prompt parity `100%`. Unauthorized, disabled and forbidden selections were all zero.

One relevant candidate was conservatively dropped because two different decisions were only
`0.0019` apart, and one one-off operation injected an irrelevant position Memory. Both outcomes are
retained without parameter changes or a holdout rerun. Full evidence is in `holdout-report.json`.
