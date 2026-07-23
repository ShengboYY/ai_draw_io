# E7 r7 source-evidence identity — Development hydration run

- Date: 2026-07-23
- Status: stopped at the pre-registered model-visible-required-evidence gate; no model run, Validation, or holdout.
- Code commit: `81a2824d4199aedc146095f8ab4573d2e2f4627d`
- Retrieval run: `drawiohydrationpdfresearch_d6eabdb94562432fa8a60e178f9aa46f`

## Execution

The opt-in real producer ran PDFBox → Tesseract (`eng+chi_sim`) → canonical evidence → chunking → Pinecone
top-40 in the temporary `material-rag-e7r7-dev-20260723` Development namespace. It indexed 106 vectors and
evaluated the five retrieval-required Development tasks; the trace also contains the one declared no-retrieval
layout task. The test completed successfully and its `finally` cleanup logged deletion of this run's vectors.

The resulting trace is
`2026-07-23-e7-r7-task-hydration-candidates-development.json`
(`fc1519ed4e02d60c03df3569064ff6304bae45cd8b5bd4dec37977c96f5a79b0`), binds the frozen corpus-lock hash
`fb4758d7da4174649eb26f2389443f56c3d0e5e17a82ced2bd7b7c9b092978f0`, and binds the source-evidence identity
manifest hash `373b050bed6e32749c329590d8228bd6b11cd801af17f5053f6c34deb86efe8e`.

## Gate results

The paired exporter created six control/raw-top8 and candidate/source-aware-top8 contexts. The contrast gate
passed: 5/5 retrieval-required tasks changed (100%, above the 20% minimum), and the visual-artifact requirements
were satisfied. The r6 model-visible-required-evidence gate then failed:

| Task | Required evidence missing from control | Required evidence missing from candidate |
| --- | --- | --- |
| `dgt-dev-01` | `dwh-handoff-type`, `dwh-sequence-type` | none |
| `dgt-dev-02` | none | none |
| `dgt-dev-03` | `dwh-canonical`, `dwh-factual-edit` | none |
| `dgt-dev-04` | `daa-version-pin` | `daa-version-pin` |
| `dgt-dev-05` | `dpw-budgets` | `dpw-budgets` |

The paired export is retained as
`2026-07-23-e7-r7-paired-hydration-development.json`
(`48526233e42db91e7ad87fb0171eafb7693d9fa782b0feb62858434953702eba`) as an auditable failed-gate artifact.

## Diagnosis and decision

This is not a source-identity propagation failure. The publisher-owned manifest resolved the canonical IDs without
reading evaluator ground truth, but several needed chunks ranked outside the model-visible top-8: the two workflow
handoff/sequence IDs appeared at rank 12 for `dgt-dev-01`; canonical/factual-edit at rank 21 for `dgt-dev-03`;
the version-pin at rank 38 for `dgt-dev-04`; and the planning budget at rank 40 for `dgt-dev-05`.

The evidence is therefore present in top-40 but unavailable to one or both top-8 arms. Per the r6/r7
pre-registration, no prompt bundle was frozen, GPT-5.5 was not called, and Validation remains closed. The next
intervention must improve source-side retrieval/ranking or evidence availability without using task required
anchors, expected answers, or evaluator ground truth to select context.
