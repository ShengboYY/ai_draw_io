# E6/E7 — evidence-grounded prompt wiring

This local-only change prepares a valid generation comparison without calling a model.

- `build_drawio_generation_prompts.py` accepts a frozen task plus hydrated evidence records and emits one prompt bundle per arm. Each record retains anchor ID, source version, page and excerpt; duplicate task/arm bundles are rejected.
- XML assertions, expected answers and evaluator-only required-anchor fields are not rendered into the model prompt. Citation output must identify `anchorId`, `sourceVersion` and `page`; the evaluator rejects foreign XML and citation locations that do not match the frozen evidence.
- The corpus audit now verifies that every generation task anchor resolves to the task's declared source version and split, and that its citation assertion matches the task's required anchor set. The current v1 task suite passes this check.

## Readiness boundary

The existing `drawio-core` E0/E1 live runner deliberately includes only text/table anchors. It excludes `visual_flow` and OCR anchors, so its raw results cannot hydrate the architecture-route or scanned-workshop generation tasks. No E6/E7 control-versus-candidate metric has been produced and Validation/Holdout remain unopened.

The required next implementation is a multimodal hydration export that supplies selected text chunks and verified visual/OCR artifacts with their source locations. Only then can the control top-8 and candidate source-aware top-8 be rendered through this prompt builder and compared on Development.
