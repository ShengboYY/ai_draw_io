# E7 r6 citation-identity safety boundary and readiness gate

- Date: 2026-07-23
- Scope: local-only re-export of the frozen r4 top-40 hydration trace. No Pinecone operation, model request, token
  use, Validation or holdout access occurred.
- Input trace: `2026-07-23-e6b-task-hydration-candidates-r4.json`, bound to its original corpus-lock from commit
  `1cc02005`; output: `2026-07-23-e7-r6-paired-hydration-identity.json`.

## Single changed variable

Fallback `retrieved:<chunkId>` evidence remains a fallback unless the **ingestion trace itself** supplies a canonical
source-evidence identity. The r6 exporter deliberately does not infer a canonical anchor from `ground-truth.json`,
task `requiredAnchors`, expected answers, XML assertions, OCR similarity or ranking signal. This prevents evaluator
gold from being transformed into model-visible citations.

The exporter additionally records `modelVisibleRequiredEvidence` only after both arms are exported. It is an
evaluator-side readiness report, not a selector input. The explicit
`--require-model-visible-required-evidence` gate writes the diagnostic then exits non-zero before prompt generation or
any model request when a retrieval-required task lacks a required anchor in either arm.

## Local result

The frozen r4 trace still contains fallback identifiers for all five retrieval-required tasks, including the
architecture route's OCR page. The overall readiness result is therefore **false** for all five tasks in both arms.
The explicit gate failed as intended, so no r6 prompt bundle, model call, quality claim, Validation or holdout run
exists.

## Gate integrity

For a future passing export, the prompt builder will record the hydration export's repository-relative path and SHA-256
in every bundle. Before any provider request, the runner re-reads that exact artifact, verifies its hash and passing
readiness flag, and requires its task/arm/evidence row to equal the bundle's model-visible evidence. A self-declared
bundle flag or a stale bundle is therefore insufficient to trigger a model call.

## Decision

Do not send another generation run. The next candidate must persist source-evidence identity/span during ingestion
and improve evidence availability without evaluator gold, while retaining the r6 readiness gate. It must be separately
pre-registered as a retrieval/hydration intervention and pass locally before any new Development prompt is frozen.
