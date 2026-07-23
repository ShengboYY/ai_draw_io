# Post-R23 evidence-decision and evaluation plan

## 1. Decision

R23 closes retrieval tuning for the current 19 Development tasks. Its final result was raw top-40
17/19, model-visible top-8 16/19, and declared visual/OCR artifact coverage 7/7. The system is
useful but cannot promise that every material-grounded Draw.io request receives all required
evidence.

The next change is therefore not another ranker. It is an explicit evidence-decision seam between
evidence preparation and canvas mutation. A material-grounded request may mutate the canvas only
when its evidence outcome is `READY`. Missing evidence, ambiguous intent, and unavailable retrieval
must remain distinguishable user-visible states.

The current 19 tasks are frozen as a diagnostic regression set. They must not be reused to choose
new query terms, weights, candidate limits, or selector rules.

## 1.1 Implementation status

The Stage A production foundation is complete at commit `37a2e2c3`, compared against the fixed
pre-implementation point `bfdceddf`. The implementation was delivered through `59ef21bc`,
`947f9eac`, `9bd7f67e`, and `37a2e2c3`.

- evidence preparation now emits distinct `NotRequired`, `Ready`, `ClarificationNeeded`,
  `InsufficientEvidence`, and `DegradedDependency` outcomes;
- source and claim ambiguity are structured router outputs and block before retrieval, including
  when `SourceMode.NONE` would otherwise qualify for the evidence-free path;
- configured retrieval, visual-verification, executor, and blob-hydration failures cannot be
  converted into `Ready` or false insufficiency;
- existing citations remain subject to current readable-evidence checks;
- user-visible streaming preserves the original insufficient/degraded outcome type;
- only `Ready` and valid `NotRequired` paths may reach drawing, with regression coverage for
  blocked paths and zero model invocation.

The implementation foundation is not the Stage A evaluation result. The fresh 30-case cohort in
Section 5 now exists as `stage-a-evidence-decision-cohort-v1.json`: it has the preregistered
12/6/4/4/4 outcome distribution, 14 blocked cases, two Ready visual/OCR cases, and 12 new document
families. Its executable setup contract binds source versions/anchors, artifacts, canvas or
conversation state, absence contracts, and dependency injections. An `independent_ai` reviewer
approved all 30 cases; the ledger and report bind the exact cohort SHA-256, so any later fixture
edit invalidates the freeze. The frozen cohort has passed the HTTP-to-evidence rendering and
no-drawer boundary with injected typed outcomes. It has not yet classified the executable setup
through the real `EvidencePreparationModule`; therefore the full 30/30 classification gate remains pending.

## 2. Existing seam

`EvidencePreparationModule` is already the correct external seam. It owns source policy,
authorization, retrieval, visual observation, hydration, sufficiency, resource leases, and evidence
bundling behind one small `prepare(...) -> PreparationOutcome` interface. Adding a second
pass-through policy module would reduce locality without adding leverage.

`EvidenceSufficiencyEvaluator` remains an internal seam of that module. Tests should cross the
public `EvidencePreparationModule` interface and assert the observable outcome and whether canvas
mutation is allowed. They should not depend on lexical-overlap internals.

The module should eventually deepen its closed outcome set rather than expose scoring details:

- `NotRequired`: layout/style or other non-factual canvas work may continue without retrieval.
- `Ready`: a bounded, authorized evidence bundle is safe to send to the grounded generator.
- `ClarificationNeeded`: the requested claim, source, or target is ambiguous; no canvas mutation.
- `InsufficientEvidence`: allowed searches completed but support is incomplete; no canvas mutation.
- `DegradedDependency`: retrieval or verification did not complete; no material-grounded mutation
  and no false claim that evidence does not exist.
- existing waiting, stale-canvas, cancellation, and retry outcomes remain operational states.

There is no automatic `PartialReady` outcome in the first version. Generating a partial factual
diagram safely would require an explicit claim plan that can prove which requested claims were
omitted. Until that interface exists, “partial” maps to clarification or insufficient evidence.

## 3. User-visible decision contract

| Request/evidence state | Outcome | Canvas mutation | Required response |
|---|---|---:|---|
| Layout/style only; no factual label or relation changes | `NotRequired` | Allowed | Continue normally |
| Material-backed claims are supported and authorized | `Ready` | Allowed | Generate with citations |
| Diagram target, requested claim, or source is ambiguous | `ClarificationNeeded` | Blocked | Ask one concrete question |
| Search completed but a requested claim lacks support | `InsufficientEvidence` | Blocked | Name the missing concept/source and offer narrowing or upload |
| Index/OCR/blob/auth verification could not complete | `DegradedDependency` | Blocked for grounded work | Report degraded retrieval and offer retry |

Optional automatic retrieval must not silently fall back to an evidence-free Drawer when the
request contains factual material-backed claims. Ordinary drawing remains available as a separate,
explicit user choice; it is not an implicit recovery path for a failed grounded request.

## 4. Safety invariants

1. Only `Ready` and `NotRequired` may reach a canvas mutation interface.
2. `NotRequired` cannot add or change factual labels, values, comparisons, or source-backed
   relationships.
3. `Ready` carries only authorized, currently readable evidence and keeps the existing lease
   lifecycle.
4. `InsufficientEvidence` means search completed; `DegradedDependency` means it did not. They must
   never be collapsed.
5. Clarification, insufficiency, degradation, stale canvas, and cancellation produce zero canvas
   writes.
6. Evaluator gold, expected answers, and required anchor IDs remain outside production decisions.
7. Model knowledge cannot silently fill a missing material-backed claim.

## 5. Fresh Development cohort

Create a new post-R23 cohort from document families that do not occur in the current 19-task
Development set or future holdout. Freeze all cases before observing a new system result.

The initial cohort contains 30 Draw.io-specific cases:

- 12 `Ready`: text, multi-evidence, visual/OCR, creation, and factual-edit requests.
- 6 `InsufficientEvidence`: missing fact, unmounted source, incomplete comparison, or absent version.
- 4 `ClarificationNeeded`: ambiguous canvas target, pronoun, source, or requested relationship.
- 4 `DegradedDependency`: vector index, OCR, blob hydration, and authorization verification failure.
- 4 `NotRequired`: layout, spacing, color, and geometry-only edits with no factual change.

Before any model call, the fixture gate requires:

- exact 30/30 outcome classification;
- zero canvas mutations across all 14 clarification/insufficient/degraded cases;
- 12/12 `Ready` cases with complete source-owned identities and required visual artifacts;
- 4/4 `NotRequired` cases completing without material retrieval;
- no document-family overlap with the current Development set, Validation, or holdout;
- a frozen corpus lock and independent case review.

## 6. Remaining evaluation stages

### Stage A — evidence-decision implementation

Deepen `EvidencePreparationModule` outcomes and make `AgentConversationService` render each outcome
explicitly. Replace the silent optional fallback for factual requests. Verify through the module
interface and the no-mutation seam.

### Stage B — E7/E8 grounded Draw.io generation

Only the 12 `Ready` cases may construct prompts. Evaluate valid editable Draw.io XML, requested
structure, claim support, citations, factual edit locality, and visual/OCR transformation. Hard
safety gates are zero unsupported material claims, zero unauthorized mutations, and valid XML for
every committed canvas. Quality thresholds must be frozen with the reviewed cohort before the run.

### Stage C — E9 online authorization, version, and recovery

Run real source revocation, version pin/latest, stale-canvas, Pinecone failure, OCR failure, blob
failure, save failure, and recovery probes. Fixture contracts alone do not pass this stage.

### Stage D — independent final holdout

An independent keeper freezes and releases the holdout only after Stages A–C and all thresholds are
frozen. Run it once without further tuning. The release report must separate retrieval completeness,
evidence-decision safety, generation quality, citation quality, visual/OCR quality, and operational
recovery.

## 7. Stop and restart rules

- Do not open R24 or tune retrieval on the current 19 cases.
- A failure in Stage A returns to the decision interface or fixture design, not to query weights.
- A failure in Stage B may change generation/citation logic only when retrieval and decision inputs
  remain frozen.
- A failure in Stage C may change authorization/recovery behavior only.
- Any implementation change after holdout exposure invalidates the release decision and requires a
  newly isolated holdout, not reuse of the exposed cases.
