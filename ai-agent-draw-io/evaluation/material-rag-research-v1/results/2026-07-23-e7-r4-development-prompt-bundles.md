# E7 r4 Development paired prompt bundles

- Date: 2026-07-23
- Input: E6b r4 paired hydration `2026-07-23-e6b-paired-hydration-r4.json`
  (`398f2d6d1fce8c8f1a2a6690fc5e72ecae1214c4664ebb1589e73a686faf6c2e`)
- Task fixture: `fixtures/drawio-generation-tasks-v2.json`
- Scope: six synthetic Development tasks only; both arms retain the original task request, editable input XML where
  applicable, evidence excerpt, citation location and verified visual/OCR artifact path.

## Frozen bundles

| Arm | File | SHA-256 | Tasks |
| --- | --- | --- | ---: |
| control/raw-top8 | `2026-07-23-e7-r4-control-development-prompt-bundles.json` | `ed38cfda2bd3a2d6ffe3793bcf094ab4fa9ae4916b0df4d29ec31d2b3ded74a0` | 6 |
| candidate/source-aware-top8 | `2026-07-23-e7-r4-candidate-development-prompt-bundles.json` | `897dd3dd6e63d961177f23c36b471833bfedbd0b8d7d77c9cb7ff96a61e1dc51` | 6 |

The prompt builder is deterministic and emits one bundle per task/arm. The architecture route image is attached for
`dgt-dev-02` in both arms; `dgt-dev-06` intentionally has no retrieved evidence or image because it is the frozen
layout-only/no-retrieval task. Builder tests pass and the generated prompts contain no evaluator-only
`requiredAnchors` or expected-answer field.

## Readiness boundary

These files are inputs, not an E7 result. A formal manifest requires provider/model endpoint and request parameters,
then actual per-call request IDs, token usage, latency and response hashes. Because no model request was made here,
there is no formal-eligible manifest, response file, quality result, cost, promotion decision or Validation run.

When explicitly authorized, run the same model and frozen parameters for the two six-task Development arms, produce
one formal manifest per arm, then evaluate paired XML/edit/citation contracts before considering Validation.
