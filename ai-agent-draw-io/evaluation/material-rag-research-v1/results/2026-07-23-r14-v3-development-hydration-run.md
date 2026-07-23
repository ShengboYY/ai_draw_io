# R14 v3 Development hydration diagnostic

- Git commit: `42380d28`
- Split: `development`
- Retrieval-required tasks: 19; no-retrieval tasks: 1
- Indexed chunks: 186
- Pinecone namespace: isolated Development namespace; run vectors confirmed deleted
- Trace: `2026-07-23-r14-v3-development-hydration-trace.json`
- Model calls: 0

The live run completed, but scoped candidate completeness failed before prompt construction:

- `dgt-dev-04`: 0 candidates where the mounted chartbook has at least 40 available chunks.
- `dgt-dev-12`: 0 candidates where the mounted chartbook has at least 40 available chunks.
- `dgt-dev-20`: 28 candidates from its single explicitly selected source, which itself has 28 chunks.

The first two are invalid empty retrieval results. The third exposed a contract defect in the old unconditional
top-40 requirement. R15 records per-source projected chunk counts and requires exact
`min(40, allowed-source chunks)` coverage. This trace is diagnostic only and is not a model input.
