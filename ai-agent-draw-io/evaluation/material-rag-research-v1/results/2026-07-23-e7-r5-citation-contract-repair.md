# E7 r5 citation-output contract repair and Development bundle freeze

- Date: 2026-07-23
- Scope: local-only pre-registered repair for the failed r4 citation-output contract. No model request, token use,
  Pinecone operation, Validation or holdout access occurred.
- Fixed inputs: the r4 paired hydration
  `2026-07-23-e6b-paired-hydration-r4.json`, active v2 task fixture, control/raw-top8 and
  candidate/source-aware-top8 arms, OpenAI GPT-5.5 request parameters, output evaluator and Development-only split.

## Single changed variable

The r4 prompt asked for `anchorId`/source/page citation fields but did not bind `anchorId` to model-visible evidence;
the model consequently returned draw.io cell IDs. In r5, the prompt builder deterministically derives
`citationOptions` from the exact hydrated evidence rows already visible to the model. It renders those complete
`anchorId`, `sourceVersion`, `page` triples as the only allowed citations and explicitly prohibits mxCell/XML/label
or generated diagram IDs. This derivation does not read `requiredAnchors`, expected answers, XML assertions or any
other evaluator-only gold.

The formal runner now constrains each structured-output citation object to one complete bundle-visible
`anchorId`/`sourceVersion`/page triple; for no-retrieval tasks it requires an empty citation array. A local
post-response check independently rejects every citation triple that does not exactly match a frozen
`citationOptions` row. This is one output-contract repair, not a retrieval, hydration, source-selection, task,
model or evaluator change.

## Frozen Development bundles

| Arm | File | SHA-256 | Tasks |
| --- | --- | --- | ---: |
| control/raw-top8 | `2026-07-23-e7-r5-control-development-prompt-bundles.json` | `d0ca377caac7de398cc17995991544e551cb44c385770761ecfa7d6eeb1ae768` | 6 |
| candidate/source-aware-top8 | `2026-07-23-e7-r5-candidate-development-prompt-bundles.json` | `51ff96d79847946530135f4ff40e30ce53c04b49c9aef51e664659d8fa598b87` | 6 |

The five retrieval-required tasks expose 8–12 frozen citation options per arm. The fixed layout-only/no-retrieval
task `dgt-dev-06` exposes zero options and requires `citations: []`. Both bundles were checked for six unique
Development task IDs and no evaluator-only `requiredAnchors`, expected-answer, XML-assertion or edit-assertion field.

## Local verification and next boundary

The full analysis test suite passed (91 tests). New tests prove that the builder freezes only visible evidence as
options, the structured request accepts only those full triples, and the local response boundary rejects both a
generated cell ID and a correct anchor paired with the wrong page.

The next operation is a newly authorized Development-only r5 paired generation: six synthetic requests per arm to
the same OpenAI GPT-5.5 endpoint, followed by formal-manifest validation and the unchanged evaluator. Validation and
holdout remain closed.
