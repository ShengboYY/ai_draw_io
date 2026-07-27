# Stage E post-calibration Validation review packet

Status: **pending two named reviewers**.

This packet covers the 12 newly frozen internal Validation tasks only. It is bound to task fixture
SHA-256 `802d84d23a84798d47e3ca36560f16c4c1c995de0c6a81f1402a8b226f71b2ee` and the complete
evidence/claim packages in
`fixtures/generated/stage-e-postcalibration-validation/{tasks,contexts,ground-truth}.json`.

It is not a review of Stage B or Stage D output, and it does not authorize a model run by itself.

## Reviewer procedure

Each reviewer records their name/role and independently verifies every task's source text, evidence
anchor, required claim, source scope and—where present—visual/OCR artifact. A reviewer should reject
a task if a claim is unsupported, an image does not show the stated flow, source scope is ambiguous,
or a frozen assertion conflicts with the requested Draw.io operation. The two reviewers must resolve
any disagreement before an approval artifact is recorded.

For the two `layout_only_edit` tasks, verify that the input XML makes it clear that only geometry may
change: content, cell IDs, structure and style must remain protected.

| Task | Operation | Frozen claim IDs |
|---|---|---|
| stge-val-01 | creation | rg-route, rg-owner |
| stge-val-02 | creation | er-retain, er-dispose |
| stge-val-03 | creation | cc-impact, cc-approval |
| stge-val-04 | structural edit | oh-handoff, oh-note |
| stge-val-05 | structural edit | be-route, be-owner |
| stge-val-06 | layout-only edit | lr-roles |
| stge-val-07 | layout-only edit | ls-states |
| stge-val-08 | version/authorization-safe edit | ca-v4, ca-scope |
| stge-val-09 | failure/recovery | rr-block, rr-resume |
| stge-val-10 | visual/OCR → editable XML | ec-flow |
| stge-val-11 | visual/OCR → editable XML | sr-flow |
| stge-val-12 | failure/recovery | ss-preserve, ss-escalate |

## Frozen claims

- `rg-route`: Release Coordinator sends a Signed Build to Change Approval before Authorized Deployment.
- `rg-owner`: Authorized Deployment is owned by Platform Operations.
- `er-retain`: Case Archive retains closed evidence for 90 days before Review Archive.
- `er-dispose`: Review Archive sends expired evidence to Certified Disposal.
- `cc-impact`: Change Request is assessed by Impact Review before Customer Notice.
- `cc-approval`: Customer Notice may be issued only after Change Authority approval.
- `oh-handoff`: Primary Responder hands an acknowledged incident to the Secondary Responder.
- `oh-note`: Secondary Responder records the handoff note before shift close.
- `be-route`: Billing Exception goes to Revenue Review before Account Correction.
- `be-owner`: Account Correction is owned by Revenue Operations.
- `lr-roles`: Submitter, Approver and Observer are the existing process roles.
- `ls-states`: Pending, Validated, and Archived are the existing lifecycle states.
- `ca-v4`: Catalog Analyst submits Version 4 to Product Approver before Catalog Publish.
- `ca-scope`: Only Version 4 is authorized for this edit.
- `rr-block`: When Evidence Retrieval is Unavailable, source-grounded generation is blocked while Manual Diagramming remains available.
- `rr-resume`: Retry Retrieval may continue only after Evidence Retrieval is restored.
- `ec-flow`: Field Request → Evidence Check; Yes → Approve Request → Issue Work Order; No → Request Missing Evidence → Await Resubmission.
- `sr-flow`: Alert Received → Assess Service; Yes → Record Resolution; No → Start Recovery → Notify Incident Lead → Reassess Service.
- `ss-preserve`: Diagram Save Failed preserves the editable canvas and offers Retry Save.
- `ss-escalate`: After a repeated save failure, the agent routes the case to Storage Support.
