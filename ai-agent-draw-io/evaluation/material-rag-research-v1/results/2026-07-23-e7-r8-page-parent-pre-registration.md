# E7 r8 page-parent evidence availability — pre-registration

- Date: 2026-07-23
- Status: local implementation and contract checks complete; no Pinecone, model provider, Validation, or holdout run.

## Single intervention

R8 adds searchable, citable `PAGE_PARENT` projections to the existing canonical retrieval representation. Each
parent contains only text Evidence from one source page and retains the page ID plus every copied Evidence ID. A
parent is capped at 900 tokenizer units; an oversized page is represented by consecutive, source-page-local parent
chunks rather than truncating it or mixing pages.

This differs from the earlier E2 neighbor window: E2 changed the embedding text of an existing leaf, while R8
adds a separately retrievable page-level projection whose model-visible text contains the page's bounded evidence.
The source-identity resolver remains unchanged: it can assign an ID only when the parent text itself satisfies an
exact publisher-owned source phrase, or when the existing visual chunk satisfies its exact visual-page rule.

For the active Development retrieval tasks, the fixture now declares `selectedMaterialVersion` as agent-visible
session scope. The producer rejects a blank or unmounted selected version before retrieval; it does not use task
required anchors, expected answers, claims, XML assertions, or `ground-truth.json` to construct or select a
parent.

## Pre-registered gates

A fresh opt-in Development hydration trace must bind the updated corpus lock and source-evidence identity manifest,
then pass both unchanged gates:

1. the paired-hydration contrast gate (at least 20% retrieval-required task contexts change); and
2. the r6 model-visible required-evidence gate for both control and candidate arms.

Failure stops before prompt freezing and GPT-5.5. Passing both gates permits freezing new Development bundles and
requesting a separate model-run authorization. Validation and holdout remain closed.
