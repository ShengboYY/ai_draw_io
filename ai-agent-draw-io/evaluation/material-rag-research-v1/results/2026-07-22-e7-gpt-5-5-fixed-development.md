# E7 — GPT-5.5 fixed multimodal evidence · Development

Six frozen synthetic Development tasks were run with the fixed E7 evidence bundles. Two requests included a synthetic source image: the architecture request-route and the scanned workshop page. Validation and Holdout were not sent.

| Metric | Result |
| --- | ---: |
| HTTP-successful model responses | 6/6 |
| Parseable editable draw.io XML | 6/6 (100.0%) |
| Frozen XML structure assertions | 6/6 (100.0%) |
| Citation assertion: anchor + source version + page | 2/6 (33.3%) |
| Full task completion | 2/6 (33.3%) |

The visual request-route task (`dgt-dev-02`) and the V1-pinning task (`dgt-dev-04`) completed. The other four outputs had usable XML but missing or nonconforming citations. In particular, the scanned-workshop output cited generated cell IDs rather than the provided evidence anchor. The XML evaluator strips draw.io HTML formatting and matches required labels on word boundaries, so styling variations are accepted but `V1` cannot match `V10`.

The first attachment attempt for the two image tasks returned HTTP 400 before a model response was created because the local artifact path was resolved relative to the wrong directory. They were retried once with the verified synthetic images attached; no text-only image task was scored.

**Decision:** do not promote or open Validation. Required-citation contract conformance is 2/6, well below the plan's 0.90 citation-completeness target. This result does not measure claim-level citation completeness, citation precision, claim correctness, or faithfulness, so it cannot establish a general generation/citation failure. It is not an E6 context-selection result and says nothing about raw top-8 versus selected top-8 quality.

Raw normalized responses: `2026-07-22-e7-gpt-5-5-fixed-development-responses.json`; strict evaluator output: `2026-07-22-e7-gpt-5-5-fixed-development-evaluation.json`.
