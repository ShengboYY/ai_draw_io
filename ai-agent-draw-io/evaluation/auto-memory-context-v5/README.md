# V1.8 multi-intent recall pre-registration

## Scope

V1.8 addresses the multi-intent failure isolated by V4. It may add a bounded recall planner and
run the existing semantic acceptance policy independently for each planned query. It must not tune
the existing `minimumScore=0.82`, `minimumLead=0.02`, `maximumScoreDrop=0.03` policy against V4 or
the new holdout.

The original request remains a fallback retrieval query. A plan may contain at most three
self-contained subqueries. Planning or one subquery failing must not make generation unavailable;
successful semantic subqueries remain usable, while an all-query technical failure falls back to
the deterministic MySQL baseline. The final Memory set still passes through MySQL authority,
Chartbook override and the existing prompt budget.

## Frozen planning cohorts

- Development cohort: `auto-memory-recall-planning-development-v1/cohort.json`
- Untouched holdout: `auto-memory-recall-planning-v1/holdout.json`
- Development SHA-256:
  `cc274217e5b29334226d1583b9b05c3f432a5152a226659cbb3512066baa0227`
- Holdout SHA-256:
  `01bd2a8694e53cb6c0bc59cbd3f8479ee4452ceed3ec4fe8bdec0176bce50e1b`
- Both cohorts contain only synthetic requests. Single-intent coordination cases are included so
  the planner is measured on abstention rather than rewarded for splitting every conjunction.
- SHA-256 values are recorded in the freeze commit before implementation begins.

## Holdout quality gate

| Metric | Required |
|---|---:|
| Exact case success | >= 87.5% |
| Multi-intent coverage | >= 87.5% |
| Single-intent abstention | >= 87.5% |
| Protocol failure | 0% |

One case succeeds only when the returned subquery count matches the label and every required
intent term group is covered by a distinct subquery. Text does not need to match verbatim. The
holdout may be run once with `deepseek-v4-pro`; it cannot be used to alter the prompt, eligibility
policy, parser or labels and then be rerun.

The planner evaluation performs no MySQL writes and does not train or fine-tune any model. A
separate selector test proves per-query confidence gating, merge order, deduplication and failure
fallback. Semantic Memory Context remains default-off after V1.8 regardless of the synthetic
result.

## V1 holdout outcome

The only V1 holdout run reached 80% exact case accuracy, 66.67% multi-intent coverage, 100%
single-intent abstention and 0% protocol failures, so it did not pass. DeepSeek returned an empty
plan for one `while` request that constrained different targets and one negative/positive request.
The V1 holdout is retired and will not be rerun.

## V1.8.1 pre-registration

The next revision may clarify the general decision boundary: different targets or properties that
could map to different Memory semantic keys are independent intents; coordinated subjects or
values sharing one property remain one intent. It may broaden only the cheap model-call eligibility
signals for contrastive wording. It must not add deterministic sentence splitting or alter the
semantic vector thresholds.

- Development-v2 SHA-256:
  `31d872aa229c7ece68e878c66c5aa220171c9cee6645b610fd5272c5c4f48019`
- Untouched V2 holdout SHA-256:
  `52566e2605bd7ae70e8fd6d3b9996f91de8d5d2f9833bfc38e479530d9df7405`
- Both cohorts include contrastive multi-target requests and single-intent controls containing
  `while`, `but`, coordinated subjects or coordinated values.
- The V2 holdout gate requires at least 90% for exact case accuracy, multi-intent coverage and
  single-intent abstention, with 0% protocol failures.

Development-v2 may be used to finalize the generic prompt distinction. The untouched V2 holdout
may run once only after that revision is committed; the failed V1 holdout cannot be counted as
independent evidence for the new revision.

Development-v2 ran once with `AUTO_MEMORY_RECALL_PLANNING_V2` and passed all ten cases: exact case
accuracy, multi-intent coverage and single-intent abstention were 100%, with 0% protocol failures.
No further prompt or eligibility changes were made. The V2 holdout remains untouched until this
revision is committed.

## V2 holdout outcome

The only V2 holdout run reached 90% exact case accuracy, 100% multi-intent coverage, 75%
single-intent abstention and 0% protocol failures. It therefore did not pass the pre-registered
abstention gate. The sole miss split `Use black but not gray borders for every service` into a
positive black-border query and a negative gray-border query even though the fixture labels that as
one shared border-color decision.

V2 is retired and will not be rerun. The result supports the bounded multi-query mechanism and
shows that the revised planner removed the observed under-splitting, but it does not approve
semantic Memory Context for release. V1.8 stops here to avoid repeatedly tuning a model prompt
against small synthetic cohorts. Both semantic and multi-intent feature flags remain default-off;
a future stage needs new end-to-end vector evidence or local real requests, not another edit to
these labels or thresholds.
