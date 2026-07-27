# E7 r4 — GPT-5.5 paired Development generation

- Date: 2026-07-23
- Commit: `0a61859a`
- Scope: six synthetic Development tasks per frozen E6b r4 arm (12 requests total). No Validation or holdout task
  was sent.
- Model contract: OpenAI `gpt-5.5`, `https://api.openai.com/v1/chat/completions`, structured JSON output,
  `reasoning_effort=low`, provider-default temperature, `max_completion_tokens=6000`, visual artifacts at `high`
  detail. Each arm has a formal-eligible manifest with prompt, response, task and corpus hashes.

| Arm | Formal manifest | XML parse | Required citation contract | Completed tasks |
| --- | --- | ---: | ---: | ---: |
| control/raw-top8 | `2026-07-23-e7-r4-gpt-5-5-control-development-run-manifest.json` | 6/6 | 1/6 | 0/6 |
| candidate/source-aware-top8 | `2026-07-23-e7-r4-gpt-5-5-candidate-development-run-manifest.json` | 6/6 | 1/6 | 0/6 |

Both manifest validation files report `valid=true` and `formalEligible=true`. Reported model usage was 54,795 input
and 12,685 output tokens, with 139,713 ms summed call latency. At GPT-5.5 standard text rates ($5/M input and
$30/M output), this is approximately **$0.65** before taxes or account-specific adjustments; image input is included
in the provider-reported input-token totals. Both manifests point to
`2026-07-23-e7-r4-gpt-5-5-run-corpus-lock.json`, the exact corpus lock from commit `0a61859a` at request time;
the active corpus lock subsequently changed only when this result was recorded.

## Result and diagnosis

The paired result is exactly tied on all currently automatable task metrics, so it does not support an E6 selector
promotion or a Validation run. `dgt-dev-02` correctly produced the editable four-stage route in both arms, showing
that the r4 OCR/artifact hydration reached the model. However, it cited generated cell IDs such as `scope` or
`scope_sources`, not the retrieved evidence anchor `daa-route-scope`; the evaluator correctly rejected them.

The same contract failure recurs across material-grounded tasks: the prompt requests citations, but does not make the
allowed evidence anchor IDs an explicit output-selection constraint. `dgt-dev-06` is the only citation-contract pass
because its frozen layout-only task requires zero citations; its geometry-only edit assertion still fails. Claim-level
correctness, faithfulness and citation precision/completeness remain `not_evaluated` because the required independent
two-reviewer claim review has not been performed.

## Decision

**Do not promote and do not open Validation.** The next candidate must be a separately pre-registered E7 prompt-output
contract repair: citations must select exact supplied evidence `anchorId` values (or none), rather than diagram cell
IDs. Re-run Development only after the repair is locally tested and the prompt bundle is re-frozen; do not interpret
the present 0/6 completion tie as an RAG retrieval-quality result.
