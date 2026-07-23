# E7 r5 — GPT-5.5 paired Development generation

- Date: 2026-07-23
- Commit at request time: `ad9a175f`
- Scope: six synthetic Development tasks for each frozen r5 arm (12 formal recorded calls). Validation and holdout
  were not sent. The run-specific corpus-lock snapshot is
  `2026-07-23-e7-r5-gpt-5-5-development-run-corpus-lock.json` (SHA-256
  `7b0c0fdc7fd45a48a92e0e90b5051ef378e9aeb92b01c88fe6099f0e5006cb65`).
- Model contract: OpenAI `gpt-5.5`, `https://api.openai.com/v1/chat/completions`, structured output
  `drawio_generation_response_v2`, `reasoning_effort=low`, provider-default temperature,
  `max_completion_tokens=6000`, `high` detail for frozen visual artifacts.

| Arm | Formal manifest | HTTP success | XML parse | Required citation contract | Completed tasks |
| --- | --- | ---: | ---: | ---: | ---: |
| control/raw-top8 | `2026-07-23-e7-r5-gpt-5-5-control-development-run-manifest.json` | 6/6 | 6/6 | 1/6 | 0/6 |
| candidate/source-aware-top8 | `2026-07-23-e7-r5-gpt-5-5-candidate-development-run-manifest.json` | 6/6 | 6/6 | 1/6 | 0/6 |

Both manifest validators report `valid=true` and `formalEligible=true`. The recorded calls consumed 65,217 input
and 13,140 output tokens with 148,415 ms summed latency. At the GPT-5.5 standard $5/M input and $30/M output rates,
the recorded formal calls cost approximately **$0.72** before taxes or account-specific adjustments. A first candidate
process ended without writing a response/manifest artifact; it has no request IDs or usage record and is not counted
as a formal result. The user explicitly authorized one candidate retry, which produced the recorded formal artifact.

## Result and diagnosis

r5 did repair the original **output-shape** error: every non-empty model citation passed the frozen
`citationOptions` triple boundary, so generated mxCell IDs were no longer emitted as citations. The response schema
and local response boundary therefore worked as intended.

However, both arms remain at 1/6 required-citation-contract rate and 0/6 completion. For each of the five
retrieval-required tasks, the task's evaluator-required anchors were absent from that task's model-visible top-8
`citationOptions`; the model could only choose alternate hydrated evidence or no citation. Examples include
`dgt-dev-02`, whose visible architecture page-3 OCR companion chunk retained a fallback `retrieved:<chunkId>` ID
instead of `daa-route-scope`/`daa-route-compose`, and `dgt-dev-04`, whose visible evidence did not include
`daa-version-pin`. The layout-only/no-retrieval `dgt-dev-06` is the sole citation-contract pass by design, but still
fails its geometry edit assertion.

This is not evidence that GPT-5.5 ignored the r5 citation contract, nor a selector-quality conclusion: r5 surfaced
an **input identity-propagation / evidence-availability failure**. Claim-level correctness, faithfulness and citation
precision/completeness remain `not_evaluated`; the required two-reviewer claim review is not meaningful while no
grounded task completes.

## Decision

**Do not promote and do not open Validation.** The next single-variable candidate must be pre-registered locally as
a citation-identity/hydration repair: preserve a verified canonical anchor when a selected source/page evidence span
supports it, without reading task `requiredAnchors` or expected answers. Before any new model request, an input
readiness gate must show the required grounded evidence is actually represented in the model-visible context; then
freeze new paired Development bundles and request separate authorization.
