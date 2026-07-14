# Production Visual Review Rollout

Production visual review has two server-side switches. Both default to `false`:

| Environment variable | Spring property | Effect |
| --- | --- | --- |
| `ZIPP_VISUAL_REVIEW_ENABLED` | `zipp.visual-review.enabled` | Allows production entry points to call reviewer `300018` and return review results. When false, post-draw review completes silently and an explicit review-only request returns `UNAVAILABLE`. |
| `ZIPP_VISUAL_REVIEW_AUTO_REPAIR_ENABLED` | `zipp.visual-review.auto-repair-enabled` | Allows a policy-approved `REPAIR` decision to start one drawer repair. It has no effect unless visual review is enabled. |

There are deliberately no per-issue switches. The repair whitelist remains versioned in `CanvasVisualReviewPolicy` and its tests.

## Rollout sequence

1. **Shadow validation**
   - Keep the primary deployment switches false. Route only sampled `/api/v1/visual-reviews/stream` requests to an isolated review deployment with review enabled and auto repair disabled.
   - The sampled client sets `shadow=true`. The server calls reviewer `300018` and records the decision, but emits no `review_result` and never starts repair.
   - Run the `production-visual-review@2` Mode C profile against the versioned `visual-review-v1` suite and a purpose-approved human sample before and during the sample.
   - Use reviewer `300018`; reviewer `300016` remains an evaluation-only comparison judge and must not receive product traffic.
   - Do not duplicate the same product request or write image payloads to logs/databases. The traffic layer selects one review endpoint and applies a dedicated shadow quota budget.
2. **Visible review**
   - Set `ZIPP_VISUAL_REVIEW_ENABLED=true` and keep auto repair false.
   - A repair-worthy result is exposed as `NEEDS_HUMAN_REVIEW`; it cannot mutate the canvas.
3. **Auto-repair canary**
   - On a sticky deployment receiving 5% of users, set both switches true.
   - Keep the remaining deployment on visible-review settings. The traffic layer, not application issue-type flags, owns the 5% allocation.
4. **Default on**
   - Set both switches true on all instances only after the gates below pass.
   - Remove the temporary `maxReviewIterations` compatibility read in the next scheduled API cleanup after clients have migrated to `maxDeterministicRepairRounds`.

## Promotion gates

- Reviewer availability is at least 99%, excluding client screenshot/export failures.
- `output_schema_error` is below 1%.
- Stale review rate is below 5% and every spike has an explained concurrent-edit cause.
- Verify-only pass rate after automatic repair is acceptable for the approved baseline.
- P50/P95 added latency and model cost, computed from the correlated LLM token records, remain within budget.
- Human samples show no material increase in undo/manual-correction behavior after repair.

Use `agent_trace_event` events `visual_review_started`, `visual_review_completed`, `visual_review_unavailable`, and `visual_review_stale`. Join their `run_id` to `agent_llm_call` for model/token cost; each call stores its pricing version, input/output price snapshot, and estimated USD cost so historical runs do not drift when prices change. Use `sourceRunId`/`repairRunId` in scalar event metadata for the mutation → review → repair chain. Images are intentionally absent from telemetry.

## Rollback

1. Set `ZIPP_VISUAL_REVIEW_AUTO_REPAIR_ENABLED=false` first to stop new automatic mutations while preserving review evidence.
2. If review itself is unhealthy, set `ZIPP_VISUAL_REVIEW_ENABLED=false`.
3. Restart or roll the affected instances and confirm no new production reviewer calls appear.
4. Do not restore agents `300011` or `300012`; deterministic analysis and the saved canvas remain the fail-open path.
