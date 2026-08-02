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
