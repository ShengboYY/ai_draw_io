# E7 — GPT-5.5 draw.io XML/citation contract pilot · Development

- Six frozen synthetic Development tasks from `drawio-generation-tasks-v1` were sent to GPT-5.5. Validation and Holdout tasks were not sent.
- Prompt contract: return JSON with `xml` and `citations`, generate editable `mxGraphModel`/`mxCell` XML, satisfy the task request, and cite the listed anchor IDs.
- This is a **response-contract smoke test**, not an evidence-grounded E7 comparison: the prompt supplied task text and anchor IDs, but not retrieved source excerpts. It therefore cannot measure context-selection quality, faithfulness, or a candidate-versus-control E6 effect.

| Metric | Result |
| --- | ---: |
| Tasks | 6 |
| Parseable XML | 3/6 (50.0%) |
| Citation assertions | 3/6 (50.0%) |
| Full XML + citation completion | 1/6 (16.7%) |

Only `dgt-dev-06` met its frozen XML and citation assertions. `dgt-dev-02` and `dgt-dev-03` produced parseable editable XML and complete citations but failed exact required-label assertions. Three responses were empty under the requested JSON contract. One successful response used citation objects (`{"anchorId": "..."}`) rather than strings; the evaluator now normalizes both documented forms so that output-shape variation is not miscounted as a citation failure.

**Decision:** no promotion, no Validation run, and no E6 conclusion. The next valid experiment must construct control and candidate prompts from the corresponding frozen retrieved context bundles (including source text, version and citation locations), then compare their task completion and citation/faithfulness outcomes on Development before opening Validation.

Raw response normalization: `2026-07-22-e7-gpt-5-5-development-responses.json`; evaluator output: `2026-07-22-e7-gpt-5-5-development-evaluation.json`.
