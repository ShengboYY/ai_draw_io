# E7 r8 page-parent evidence availability — Development hydration run

- Date: 2026-07-23
- Status: stopped at the multimodal-artifact input contract; no paired bundle, model run, Validation, or holdout.
- Code commit: `b0e06ce55a798a8e555f0890ec964f4f8e6e59fa`
- Retrieval run: `drawiohydrationpdfresearch_df3962c69e1e47eb8d554e1a0e7980c1`

## Execution and cleanup

The opt-in producer ran real PDFBox → Tesseract (`eng+chi_sim`) → canonical evidence → R8 retrieval projection →
Pinecone top-40 in the temporary `material-rag-e7r8-dev-20260723` Development namespace. It indexed 80 vectors
for the five retrieval-required Development tasks; the trace also includes the declared no-retrieval layout task.
The live test completed successfully, so `runRetrievalExperiment` completed its `finally` deletion and deletion wait
before the trace was written. No model-provider request was made.

The trace is [2026-07-23-e7-r8-development-hydration-trace.json](2026-07-23-e7-r8-development-hydration-trace.json)
with SHA-256 `a678e3561bf5eef2a565d44cf79185faf0ef3faf050eae11546f85208afdddee`. It binds corpus-lock
`7a10b6f009326f6f871af1ce008fb11fa18887810f932ef9d5ba5b1b16072341` and the publisher-owned source-identity
manifest `373b050bed6e32749c329590d8228bd6b11cd801af17f5053f6c34deb86efe8e`.

## Gate result

The raw source-aware selector changed 4/5 retrieval-required task contexts (80%), which would exceed the 20%
contrast threshold. The paired exporter nevertheless rejected the run before producing contexts: `dgt-dev-02` needs
an architecture page-3 visual/OCR artifact in each arm, but neither raw top-8 nor source-aware top-8 contained one.
In this R8 trace the matching architecture page-3 text row was rank 22 and its VISUAL row was rank 24; the r7 trace
had those rows at ranks 4 and 3 respectively. Therefore the multimodal input contract, not a model-visible evidence
or generation score, is the failed gate.

No prompt was frozen and no GPT/DeepSeek, Validation, or holdout run was started. The next intervention must restore
the required visual/OCR artifact availability without inspecting evaluator anchors, expected answers, or XML claims.
