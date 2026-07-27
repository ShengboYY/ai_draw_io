# E7 — GPT-5.5 draw.io XML/citation contract pilot · Development

- Six frozen synthetic Development tasks from `drawio-generation-tasks-v1` were sent to GPT-5.5. Validation and Holdout tasks were not sent.
- Prompt contract: return JSON with `xml` and `citations`, generate editable `mxGraphModel`/`mxCell` XML, satisfy the task request, and cite the listed anchor IDs.
- This is a **response-contract smoke test**, not an evidence-grounded E7 comparison: the prompt supplied task text and anchor IDs, but not retrieved source excerpts. It therefore cannot measure context-selection quality, faithfulness, or a candidate-versus-control E6 effect.

| Metric | Result |
| --- | ---: |
| Tasks | 6 |
| Parseable XML | 3/6 (50.0%) |
| Citation assertions (anchor + version + page) | 0/6 (0.0%) |
| Full XML + citation completion | 0/6 (0.0%) |

Three responses contained parseable editable XML, but none provided a complete citation object with matching anchor ID, source version and page. `dgt-dev-02` and `dgt-dev-03` also failed exact required-label assertions. Three responses were empty under the requested JSON contract. The original pilot prompt only requested anchor IDs, so this outcome confirms that it was not a valid citation-location test.

**Decision:** no promotion, no Validation run, and no E6 conclusion. The next valid experiment must construct control and candidate prompts from the corresponding frozen retrieved context bundles (including source text, version and citation locations), then compare their task completion and citation/faithfulness outcomes on Development before opening Validation.

Raw response normalization: `2026-07-22-e7-gpt-5-5-development-responses.json`; evaluator output: `2026-07-22-e7-gpt-5-5-development-evaluation.json`.
