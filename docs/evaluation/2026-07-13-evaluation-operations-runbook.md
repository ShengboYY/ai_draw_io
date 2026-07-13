# Evaluation Operations Runbook

## 1. Scope and authority

This runbook covers the transition from an immutable Release Eval Run to canary observation and regression Case maintenance. The Evaluation service produces recommendations only. It has no deployment, promotion, pause or rollback capability.

- A Release Owner may submit aggregate canary windows only after the referenced Eval Run has Gate `PASS`, or `BLOCK` with an approved override.
- `HALT_RECOMMENDED` requires a human release decision; it does not roll back automatically.
- `NO_DECISION` means evidence is insufficient or unreliable. It must never be interpreted as `CONTINUE`.
- Production identifiers stop at the reviewed Candidate boundary. Published Case artifacts and Case Health records remain synthetic and non-sensitive.

Evaluation identities must first be admitted through `ADMIN_EMAILS`. Optional `ADMIN_EVAL_EDITOR_EMAILS` and `ADMIN_EVAL_REVIEWER_EMAILS` narrow those users to the corresponding Evaluation role; `ADMIN_RELEASE_OWNER_EMAILS` grants Release Owner operations. Users admitted through `ADMIN_EMAILS` but omitted from the narrower lists retain Eval Admin privileges.

## 2. Deployment adapter contract

The deployment platform posts aggregate metrics to:

```text
POST /api/v1/admin/eval-operations/canary-assessments
```

The caller must use an authenticated Release Owner session, the normal CSRF contract, and a same-origin or allow-listed origin. The payload is:

```json
{
  "evalRunId": "er_release_123",
  "deploymentRef": "deploy-2026-07-13.1",
  "policyVersion": "canary-policy-v1",
  "baseline": {
    "requests": 1000,
    "failures": 10,
    "criticalFindings": 0,
    "infrastructureErrors": 1,
    "p95LatencyMs": 900,
    "averageCost": 0.012
  },
  "canary": {
    "requests": 120,
    "failures": 2,
    "criticalFindings": 0,
    "infrastructureErrors": 0,
    "p95LatencyMs": 940,
    "averageCost": 0.0125
  },
  "policy": {
    "minimumRequests": 100,
    "maximumFailureRateIncrease": 0.02,
    "maximumLatencyRatio": 1.2,
    "maximumCostRatio": 1.2,
    "maximumInfrastructureErrorRate": 0.05
  }
}
```

Counts, latency and cost must be non-negative; failures and infrastructure errors cannot exceed request count. Store only aggregate windows—never prompt, response, XML, trace, user or request ID.

## 3. Recommendation handling

| Outcome | Meaning | Operator action |
| --- | --- | --- |
| `CONTINUE` | Enough traffic and every configured relative threshold is within bounds | Record the release decision and continue normal observation |
| `HALT_RECOMMENDED` | A critical finding exists, or failure/latency/cost exceeds policy | Page the Release Owner, inspect Eval evidence and deployment telemetry, then decide pause/rollback outside this service |
| `NO_DECISION` | Traffic is below the minimum or infrastructure errors make the window unreliable | Extend the window or repair telemetry/infrastructure; do not promote based on this result |

Critical findings are evaluated before infrastructure noise and therefore always produce `HALT_RECOMMENDED`. Every assessment is persisted in `eval_canary_assessment` and written to `admin_audit_log`. Non-`CONTINUE` outcomes emit the structured log event `eval-canary-recommendation`; production log routing should page the configured release channel.

## 4. Case Health operations

Enable the nightly job only after the Eval Run database and Published Case artifact store are configured:

```text
zipp.evaluation.case-health-job-enabled=true
zipp.evaluation.case-health-job-cron=0 30 2 * * *
```

The job reads completed Eval Runs, excludes referenced baseline runs from candidate stability samples, and maintains:

- `FLAKY`: eligible repetitions contain both PASS and FAIL;
- `BROKEN_BASELINE`: the referenced baseline did not reproduce the expected failure;
- `ALWAYS_PASS_REVIEW`: at least five eligible candidate samples all pass; review whether the Case is still valuable;
- `STALE_REVIEW`: the Case has not been updated for 180 days;
- `UNSCORABLE`: no PASS/FAIL sample exists;
- `HEALTHY`: none of the maintenance conditions apply.

`eval_case_health` contains only Case ID/version, baseline reproduction, status, summary and timestamp. It cannot contain source run, production user, trace or payload. `eval-case-health-alert` is emitted when flaky or broken-baseline Cases exist; route that structured event to the Evaluation owner channel.

Admins can inspect and manually refresh the queue at `/admin/eval-operations`. A refresh is a maintenance calculation, not an Eval rerun.

## 5. Regression feedback loop

1. Open `/admin/eval-candidates` and review deterministic, Semantic Miner or Visual Miner findings.
2. Prepare a synthetic draft, run privacy validation and dry-run, then require human review.
3. Publish the approved Case and add its exact version to a Dataset.
4. Implement the fix and start a candidate Eval Run against the pinned baseline.
5. Inspect Episode evidence, statistics and Release Gate. A completed run is not itself a Gate pass.
6. After an external deployment decision, post aggregate canary windows and monitor the recommendation.
7. Let nightly Case Health detect flaky, stale or broken regression assets.

The UI visualizes this sanitized flow but intentionally does not preserve a permanent Candidate-to-production-run backlink after Case publication. This prevents a Published Dataset from becoming a route back to production identity.

## 6. Incident checks

- `Release Gate decision is unavailable`: evaluate the Release Gate first.
- `Release Gate is not eligible`: the Gate is `NO_DECISION`, or `BLOCK` lacks an approved Release Owner override.
- `NO_DECISION / insufficient requests`: extend the observation window; do not lower the threshold ad hoc.
- `NO_DECISION / infrastructure error rate`: fix the metrics or provider path, then submit a new immutable assessment.
- Case Health empty: verify completed runs contain episodes and exact Case versions exist in the Published Case store.
- Case Health job failure: inspect the safe error class in logs; never log a trace or payload while debugging the scheduled job.

## 7. Deployment checklist

- Apply `docs/sql/migrations/2026-07-13-create-eval-canary-assessment.sql`, `2026-07-13-version-eval-case-health.sql`, and `2026-07-13-clear-published-eval-candidate-links.sql`.
- Configure Release Owner identities and CSRF/origin policy.
- Connect deployment telemetry to the aggregate assessment endpoint.
- Route `eval-canary-recommendation` and `eval-case-health-alert` structured logs to operator alerting.
- Enable the Case Health schedule in one scheduler instance only.
- Verify `/admin/eval-operations` can read recommendations and health records.
- Run a low-traffic test and confirm `NO_DECISION`; run a synthetic critical-finding test and confirm `HALT_RECOMMENDED` without any deployment action.
