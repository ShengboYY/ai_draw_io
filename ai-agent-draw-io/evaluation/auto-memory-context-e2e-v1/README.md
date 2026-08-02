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
- Development SHA-256: `b8eb9c7165de456ae3e02b7c89a0ae5961308f822719202948731dab9badaab0`
- Holdout SHA-256: `dc455c682561af2f9c8b7d29cb7f2447c6ee929d725c440840d5149265063996`
- These values are recorded in the freeze commit before the evaluation harness is implemented.

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
