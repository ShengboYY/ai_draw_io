# Stage B claim-review packet

Status: **pending two named independent reviewers**.

This review covers the 20 frozen factual claims in the formal E7/E8 Development run only. The
formal source is commit `3926c012`, response SHA-256
`4b436e01c915d0e6d3af6eacb5712e9e173bc88ee581076fa358f9c77f1c8dee`.

Do not review the separately archived unqualified diagnostic responses. They are not part of E7/E8.

## Reviewer procedure

Each reviewer must record their name/role and assess every claim against the model XML, returned
citation object, and frozen evidence in `fixtures/generated/stage-a-generation/contexts.json`.
For every claim, record five booleans: `claimAnswered`, `hasCitation`, `citationSupportsClaim`,
`claimCorrect`, and `faithful`. Resolve disagreements before creating the final
`material-rag-claim-review-v1` JSON accepted by the evaluator.

| Task | Frozen claim IDs |
|---|---|
| stagb-dev-01 | rg-gateway, rg-worker, rg-audit-store |
| stagb-dev-02 | ic-commander, ic-communications, ic-escalation-window |
| stagb-dev-03 | dr-archive-retention |
| stagb-dev-04 | pc-two-person, pc-control-number |
| stagb-dev-05 | nz-v3-quarantine |
| stagb-dev-06 | so-canonical-name |
| stagb-dev-07 | cc-owner, cc-approval-window |
| stagb-dev-08 | wv-exception-flow |
| stagb-dev-09 | fs-handoff-flow |
| stagb-dev-10 | rg-rollback |
| stagb-dev-11 | cp-steady-limit, cp-failover-limit |
| stagb-dev-12 | ic-commander, ic-communications |

## Frozen claim text

- `rg-gateway`: Gateway validates ingress and forwards approved traffic to Worker.
- `rg-worker`: Worker executes the release workload after Gateway validation.
- `rg-audit-store`: Audit Store records the immutable release audit event from Worker.
- `ic-commander`: Incident Commander owns the decision lane and assigns response priority.
- `ic-communications`: Communications Lead receives the Commander handoff and owns stakeholder updates.
- `ic-escalation-window`: Unresolved severity-one incidents reach Incident Commander within 15 minutes.
- `dr-archive-retention`: Archive states `Retention: 7 years`.
- `pc-two-person`: Review approves Release through a two-person approval control.
- `pc-control-number`: The Review-to-Release control identifier is PC-204.
- `nz-v3-quarantine`: Public Zone to Private Zone traffic passes through Quarantine Path in Version 3.
- `so-canonical-name`: API Gateway, Billing Worker, and Audit Store have the stated canonical owners.
- `cc-owner`: Database Change belongs to DBA Owner and Application Change to Application Owner.
- `cc-approval-window`: Both changes require Tue 10:00–12:00 approval.
- `wv-exception-flow`: Intake → Inspect; exceptions → Supervisor Review; cleared items → Dispatch.
- `fs-handoff-flow`: Field Technician records handoff; No returns to Field Technician; Yes goes to Operations Lead.
- `rg-rollback`: Failed post-release verification rolls back to the prior stable version.
- `cp-steady-limit`: Steady-state capacity is 800 requests per second.
- `cp-failover-limit`: Failover capacity is 1200 requests per second.
