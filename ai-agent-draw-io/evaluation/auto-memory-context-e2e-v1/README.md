# Auto Memory Context end-to-end V1 pre-registration

## Evaluated path

The evaluation executes the production boundaries in this order:

1. `AUTO_MEMORY_RECALL_PLANNING_V2` with `deepseek-v4-pro` produces zero to three subqueries.
2. `PineconeAutoMemoryVectorStoreAdapter` embeds and searches an isolated namespace containing
   `eval`; existing `0.82 / 0.02 / 0.03` query and candidate gates remain unchanged.
3. `MySqlAutoMemoryAdapter` revalidates vector identities against a local MySQL authority, including
   owner, USER/current CHARTBOOK scope, ACTIVE state and current vector revision.
4. `AutoMemoryContextSelector` applies Chartbook same-key override and the existing prompt budget.
5. `PlainGenerationPromptRenderer` renders the selected context; scoring uses the exact Memory lines
   visible to the final model, not raw Pinecone hits.

No final drawing model is called because drawing quality is outside the Memory injection question.
The evaluation makes no production writes. It creates randomly named local MySQL fixtures and an
isolated Pinecone eval namespace, then deletes and verifies all vectors and database records.

## Frozen datasets

- Development: `auto-memory-context-e2e-development-v1/cohort.json`
- Untouched holdout: `auto-memory-context-e2e-v1/holdout.json`
- Both contain multi-intent, single-intent, negative, USER/CHARTBOOK override, disabled duplicate,
  unauthorized duplicate and more active noise than the per-scope SQL window.
- Development SHA-256: `7fd96c2abd033cb094857705e7eb26dde9e7406bababe167f1b6e63476065f57`
- Holdout SHA-256: `5cc47ed2a1331a51ccacb5e055e622f3e187544b39beb168661395a053271a8f`
- These values are recorded before the first result-producing evaluation run.

The initial freeze used `style` in several semantic keys. The production sanitizer rejected those
fixtures before any model or vector call, so the keys were renamed to domain properties such as
`pattern`, `weight`, and `emphasis`; both hashes above were re-frozen before a result-producing run.

## Holdout gate

| Metric | Required |
|---|---:|
| Target recall in final Prompt | >= 80% |
| Complete positive Case rate | >= 75% |
| Prompt precision | >= 80% |
| Irrelevant Prompt injection | <= 20% |
| Negative Case injection | <= 25% |
| Forbidden selection | 0% |
| Unauthorized selection | 0 |
| Disabled selection | 0 |
| Selected-to-Prompt parity | 100% |

The holdout may run once after the harness and development decisions are committed. It cannot be
used to alter the planner, vector thresholds, labels or gate and then be rerun. A synthetic pass
still does not authorize production rollout; semantic and multi-intent flags remain default-off
until local-real-request evidence is separately reviewed.

## Development result

The frozen development cohort ran once through the complete path on 2026-08-02 and passed its
pre-registered gate:

| Metric | Result |
|---|---:|
| Target recall in final Prompt | 90.9% |
| Complete positive Case rate | 85.7% |
| Prompt precision | 100% |
| Irrelevant / negative injection | 0% / 0% |
| Forbidden / unauthorized / disabled selection | 0% / 0 / 0 |
| Selected-to-Prompt parity | 100% |
| SQL-only target recall | 0% |

The one incomplete Case was the Chinese Chartbook request for horizontal async lanes plus a
triangular Pager node. The planner produced both correct facets, but the existing vector acceptance
policy retained only the Pager Memory. No threshold or prompt was changed after this result.

## One-shot holdout result

The untouched holdout ran exactly once on 2026-08-02. It failed the recall gates, so this result must
not be used to authorize production rollout. Report SHA-256:
`391c447190b2c6627ff2f650c6a1eca9be9b106c34bd7ce356ecf186da2d7592`.

| Metric | Required | Result |
|---|---:|---:|
| Target recall in final Prompt | >= 80% | **70%** |
| Complete positive Case rate | >= 75% | **66.7%** |
| Prompt precision | >= 80% | 87.5% |
| Irrelevant Prompt injection | <= 20% | 12.5% |
| Negative Case injection | <= 25% | 0% |
| Forbidden / unauthorized / disabled selection | 0 | 0 / 0 / 0 |
| Selected-to-Prompt parity | 100% | 100% |
| SQL-only target recall | diagnostic | 0% |

Three of ten target Memories were missed:

- The planner correctly split the Chinese worker-position and incident-color request, but the worker
  target scored `0.8162`, below the fixed `0.82` floor.
- The planner returned no facets for `Sources stay left; sinks stay right`; the untouched query put
  both targets first, but at `0.8091` and `0.8039`, below the floor.
- The incident-color facet also admitted an unrelated warning-color Memory in the same near-score
  cohort, producing the only irrelevant final-Prompt entry.

The next iteration should be evaluated on a new development/holdout version. It should address
planner abstention on strong clause boundaries and candidate disambiguation together; simply lowering
the vector floor would also admit the observed near-tie noise and is not supported by this result.
The semantic and multi-intent flags remain default-off.

## Verification and cleanup

- The normal offline reactor suite passed after the evaluation: 2,054 tests, zero failures, zero
  errors, and 24 opt-in skips (including this live evaluation).
- The isolated Pinecone vectors were deleted and absence was verified by the harness.
- A read-only local MySQL check found zero `amctx_%` Memory or Chartbook fixture rows.
