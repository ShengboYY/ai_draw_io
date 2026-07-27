# E7 r7 source-evidence identity persistence — pre-registration

- Date: 2026-07-23
- Status: pre-registration recorded before the live trace; the subsequent Development hydration result is recorded in
  [2026-07-23-e7-r7-development-hydration-run.md](2026-07-23-e7-r7-development-hydration-run.md).

## Single intervention

The ingestion research producer reads the frozen, publisher-owned
`fixtures/generated/source-evidence-identities-v1.json`. Each entry declares a stable source evidence ID, source
version, page and one exact match mode:

- `exact_text`: a normalized exact source phrase must occur in the retrieved text.
- `visual_page`: only a retrieved `VISUAL` chunk on that exact source page receives the declared visual identity.

The producer writes the manifest path/hash into its hydration trace. Its hydration identity-assignment path never
reads task requirements, expected answers or `ground-truth.json`; those remain evaluator-only inputs for scoring.
Unmatched evidence remains
`retrieved:<chunkId>`.

## Pre-registered gates

Before another Development model run, a fresh opt-in live trace must:

1. bind the source evidence identity manifest hash;
2. pass the existing paired-hydration contrast gate; and
3. pass the r6 model-visible required-evidence gate for both arms.

Only then may Development prompt bundles be frozen and a separate model-run authorization be requested. Validation
and holdout remain closed.
