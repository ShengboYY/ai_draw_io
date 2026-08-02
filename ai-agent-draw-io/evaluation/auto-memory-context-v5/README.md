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
