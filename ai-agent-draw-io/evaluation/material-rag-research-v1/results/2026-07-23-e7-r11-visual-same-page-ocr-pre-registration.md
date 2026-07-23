# E7 r11 visual same-page OCR representation — pre-registration

- Date: 2026-07-23
- Status: local implementation and review repair complete; no Pinecone, model provider, Validation, or holdout run.

## Purpose

R9's Development trace stopped before paired context export because `dgt-dev-02` did not retrieve the architecture
page-3 visual/OCR artifact in its top eight (VISUAL rank 28; OCR TEXT rank 34). Inspection of the retrieval projection
found that the visual dense input was effectively its caption, while its page-local process words were represented as
separate TEXT Evidence. This leaves a draw.io flow diagram weakly represented for a request that names its process
route.

## Single intervention

For every VISUAL Evidence unit, R11 appends only same-page, nonblank OCR TEXT Evidence or NATIVE TEXT Evidence whose
source region spatially overlaps the visual, provided the complete addition remains within the existing 420-token
visual limit. The VISUAL Evidence remains `PRIMARY` and the caption remains `CAPTION`; each copied text unit is an
explicit `CONTEXT` mapping. OCR from another page and NATIVE text outside the visual region are never used. The
`visual-same-page-ocr-v1` draft was rejected in review because selected architecture-page OCR was absorbed by stronger
native text before Evidence creation; `visual-same-page-text-context-v2` is the corrected projection fingerprint.

This changes the visual chunk's dense and lexical representation, not the citation boundary. It is source-independent:
it applies before requests are evaluated and does not inspect task IDs, required anchors, expected answers, XML/edit
assertions, evaluator ground truth, selected source versions, model outputs, or rank positions.

## Confirmed test seam and local checks

The confirmed public seam remains the Development hydration/export path. Local tests assert that a visual chunk receives
same-page OCR and spatially overlapping native labels, but not another page's OCR or same-page text outside the visual;
it remains dense-and-lexical and citable, preserving visual/caption/context mapping roles. The real architecture
PDF/OCR projection test confirms the page-3 editable request-route visual has a text `CONTEXT` mapping.

`RetrievalChunkBuilderTest` passes 13/13. `ControlledPdfDenseRecallLiveTest` passes 16/16, with five opt-in Pinecone
tests skipped by design. The ingestion-worker reactor main-code package build and 97 Python analysis tests also pass.
No Pinecone, model provider, Validation, or holdout was run. A local Maven-cache refresh was needed only to make the
already compiled reactor dependencies visible to the worker test; it did not make a network or Pinecone request.

## Pre-registered gates

Before any prompt freezing or provider request, run one newly authorized temporary Pinecone Development trace with the
R11 fingerprint and R10 `embeddingInputManifest`. Bind the trace to its run-time corpus lock and source-identity
manifest, then require all of:

1. the declared visual/OCR artifact in both exported control and candidate top-eight contexts;
2. the paired-hydration contrast gate (at least 20% of retrieval-required task contexts change); and
3. the unchanged r6 model-visible required-evidence gate for both arms.

Failure stops before prompt bundles, GPT/DeepSeek, Validation, or holdout. R11 is not a system-quality promotion and
does not change the agent's production behavior unless a later pre-registered evaluation passes.
