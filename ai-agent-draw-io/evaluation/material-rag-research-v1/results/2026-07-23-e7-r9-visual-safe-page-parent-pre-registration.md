# E7 r9 visual-safe page-parent routing — pre-registration

- Date: 2026-07-23
- Status: local implementation and contract checks complete; no Pinecone, model provider, Validation, or holdout run.

## Single intervention

R9 retains R8's page-local, citable `PAGE_PARENT` representation and its lexical projection, but changes its index
mode from dense-and-lexical to lexical-only. The purpose is to keep broad page-level text from occupying dense
candidate slots needed by verified visual/OCR chunks. The R8 Development trace showed the architecture page-3 text
and VISUAL artifact fall to ranks 22 and 24 for the editable request-route task, so its paired export was rejected
before model evaluation.

This is a representation-routing change only. It does not consult task required anchors, expected answers, XML
assertions, evaluator ground truth, selected source versions, or model outputs. Leaf text, visual/OCR chunk creation,
source identity resolution, chartbook scope, query text, ranking algorithm, and top-8 budget remain unchanged.

## Confirmed test seam

The public seam is the Development hydration/export path. Local contract tests will verify that page parents remain
citable and lexical-searchable while they are excluded from the dense index consumed by that path; the opt-in
Development trace must then show the declared visual/OCR artifact in both exported top-8 arms.

## Pre-registered gates

Before any prompt freezing or provider request, a new opt-in Development trace must bind a new corpus lock and source
identity manifest, then pass all of:

1. the declared multimodal visual/OCR artifact contract for both control and candidate top-8 contexts;
2. the paired-hydration contrast gate (at least 20% retrieval-required task contexts change); and
3. the unchanged r6 model-visible required-evidence gate for both arms.

Failure stops before prompt bundles, GPT/DeepSeek, Validation, or holdout.
