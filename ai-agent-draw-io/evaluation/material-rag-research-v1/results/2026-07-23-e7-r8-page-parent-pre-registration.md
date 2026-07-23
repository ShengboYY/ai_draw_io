# E7 r8 page-parent evidence availability — pre-registration

- Date: 2026-07-23
- Status: local implementation, P1 review fixes, and contract checks complete; no Pinecone, model provider,
  Validation, or holdout run.

## Single intervention

R8 adds searchable, citable `PAGE_PARENT` projections to the existing canonical retrieval representation. Each
parent contains only text Evidence from one source page and retains the page ID plus every copied Evidence ID. A
parent is capped at 900 tokenizer units; an oversized page is represented by consecutive, source-page-local parent
chunks rather than truncating it or mixing pages.

This differs from the earlier E2 neighbor window: E2 changed the embedding text of an existing leaf, while R8
adds a separately retrievable page-level projection whose model-visible text contains the page's bounded evidence.
The source-identity resolver remains unchanged: it can assign an ID only when the parent text itself satisfies an
exact publisher-owned source phrase, or when the existing visual chunk satisfies its exact visual-page rule.

For the active Development retrieval tasks, the fixture now declares `selectedMaterialVersion` as an agent-visible
selection-validation field. The producer rejects a blank or unmounted selected version before retrieval, but it does
not filter the chartbook's mounted sources or alter their ranking: doing so would introduce a second intervention to
the paired chartbook comparison. It also does not use task required anchors, expected answers, claims, XML
assertions, or `ground-truth.json` to construct or select a parent.

## Review-fix verification

The parent builder now reads source-page text Evidence directly, rather than reusing leaf retrieval text. That preserves
source-page locality when a leaf's convenience heading or table-header context originates on a different page. The
local contract covers that cross-page case, multiple parent chunks for an oversized page, exact copied primary
Evidence IDs, and an actual PDF/OCR projection containing a searchable, citable parent on the selected visual page.

## Pre-registered gates

A fresh opt-in Development hydration trace must bind the updated corpus lock and source-evidence identity manifest,
then pass both unchanged gates:

1. the paired-hydration contrast gate (at least 20% retrieval-required task contexts change); and
2. the r6 model-visible required-evidence gate for both control and candidate arms.

Failure stops before prompt freezing and GPT-5.5. Passing both gates permits freezing new Development bundles and
requesting a separate model-run authorization. Validation and holdout remain closed.
