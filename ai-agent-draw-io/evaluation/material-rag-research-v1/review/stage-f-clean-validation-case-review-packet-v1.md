# Stage F clean internal Validation case-review packet

Status: **pending two named reviewers; no model run authorized**.

This packet covers 12 new local synthetic Draw.io tasks. The cohort was authored after the Stage E
duplicate-run diagnostic and is bound to the freeze record at
[`stage-f-clean-validation-freeze.json`](../results/stage-f-clean-validation-freeze.json). It is
unseen by the generation model, but it is an internal Validation cohort rather than an external
final holdout.

Each reviewer must verify the request, every source fact, the required claim, source version, page,
and the stated protected-edit boundary. Reviewers should also inspect the two attached source images.

| Task | Type | Source version | Required facts / review focus |
|---|---|---|---|
| stgf-val-01 | creation | sf-incident-briefing:v1 | Incident Commander → Executive Review → Recovery Decision; Operations Lead ownership; includes a long label. |
| stgf-val-02 | creation | sf-release-exception:v1 | Release Exception → Risk Assessment → Change Owner; record in Deployment Register. |
| stgf-val-03 | structural edit | sf-vendor-onboarding-edit:v1 | Vendor Intake → Security Screening → Contract Setup; Procurement Operations ownership. |
| stgf-val-04 | structural edit | sf-access-review-edit:v1 | Access Request → Entitlement Review → Manager Approval → Access Record. |
| stgf-val-05 | layout only | sf-layout-escalation:v1 | Only geometry may change; preserve Duty Manager, Incident Lead, Communications Owner and existing styles. |
| stgf-val-06 | layout only | sf-layout-evidence:v1 | Only geometry may change; preserve Captured, Verified, Retained and existing styles. |
| stgf-val-07 | version/auth | sf-catalog-approval:v7 | Only Version 7; Catalog Analyst → Product Steward → Catalog Publish. |
| stgf-val-08 | recovery | sf-evidence-sync-recovery:v1 | Sync outage blocks grounded publishing; Manual Notes remains available; retry only after restore. |
| stgf-val-09 | recovery | sf-canvas-save-recovery:v1 | Preserve editable diagram; Retry Save; repeated failure routes to Workspace Support. |
| stgf-val-10 | visual/XML | sf-change-closure-visual:v1 | Inspect `change-closure-source.png`; retain both Yes/No branches and their editable semantics. |
| stgf-val-11 | visual/XML | sf-privacy-escalation-visual:v1 | Inspect `privacy-escalation-source.png`; retain both notice-required branches and their editable semantics. |
| stgf-val-12 | creation | sf-service-restoration:v1 | Service Triage → Restoration Work → Service Verification; record Incident Timeline. |

Review approval means the two reviewers agree the frozen source/evidence/claim package is complete,
correct, source-scoped and appropriate for one run. It does **not** authorize a model call: the user
must authorize that separately after both approvals are recorded.
