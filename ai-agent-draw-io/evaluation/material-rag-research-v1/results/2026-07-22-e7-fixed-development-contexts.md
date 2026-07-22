# E7 — fixed multimodal Development contexts

Six Development-only E7 prompt bundles are frozen from source-backed evidence records. Every record has an anchor ID, source version, page and excerpt; the two visual/OCR tasks also retain a local source artifact path for the multimodal model request.

| Input kind | Tasks |
| --- | ---: |
| Text/table source excerpts | 4 |
| Attached visual route or scanned page | 2 |

The context audit verifies that each bundle has exactly the task's required anchors and that every anchor resolves to the declared source version and page. The model-visible prompt contains no XML assertion or expected-answer field.

This is an **E7 fixed-context** input, used to assess editable XML and citation behavior when evidence is supplied. It is not an E6 context-selection result: no raw top-8 versus selected top-8 comparison has been made. The next model run will use only these six synthetic Development bundles; Validation and Holdout remain unopened.

Prompt bundles: `2026-07-22-e7-fixed-development-prompt-bundles.json`.
