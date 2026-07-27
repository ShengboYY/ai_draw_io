# E6a — multi-source context-selection effectiveness · Development

This local-only comparison reuses the frozen 26-case E4b multi-source Development retrieval pool. It does not call a generation model and does not open Validation or any holdout data.

Input raw SHA-256 is `c290ba6b0d97a9572435cda0d512cddfa66ff2b0f88e8d19c89c0ab277a00f07` (run `controlledpdfresearch_6a6865a0af3845468a981c5a29fe9a15`, retrieval commit `72f817ed83117c0c1688e7efc800326efbfe4baa`, corpus lock `e5c8b425df70492383e3e3cff1259d5d1b5053b8e2274f984416c2e109659bc1`). The output JSON also records the selector script SHA-256.

| Metric | Raw top-8 control | Source-aware top-8 candidate |
| --- | ---: | ---: |
| Mean anchor-level gold-evidence recall | 0.5096 | 0.5673 |
| Mean distinct source count | 2.4615 | 3.9615 |

The paired recall delta is +0.0577 with a deterministic 10,000-sample paired-bootstrap 95% interval of [0.0000, 0.1346]. The paired distinct-source delta is +1.5000, 95% interval [1.1923, 1.8077]. The 26 cases are all `multiEvidence`: English n=13 changed 0.5385→0.5962 and Chinese n=13 changed 0.4808→0.5385.

The candidate differs from the control on 26/26 cases (100%), above the pre-run effectiveness gate of 20%. E6a therefore establishes that the selector creates a real experimental contrast on multi-source chartbook retrieval. Because the recall interval touches zero and this is a single-category n=26 diagnostic, the recall increase is descriptive rather than a promotion claim. It does **not** establish generation quality: these retrieval cases are not one-to-one mappings to the draw.io generation tasks and do not contain hydrated task prompts.

The prior `2026-07-22-e6-drawio-core-development-contexts.json` is retained as a historical negative diagnostic: its 47 single-source cases produced identical control and candidate top-8 contexts and must not be used as an E6 promotion result.

**Decision:** E6a is a valid contrast check and clearly improves source coverage; evidence-recall direction is positive but not conclusive. Before an E6b/E7 model comparison, export paired task-level control/candidate hydration for the active v2 generation tasks, freeze both prompt bundles and a complete run manifest, then run Development only.
