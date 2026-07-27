# R23 formal Development run blocked by Pinecone embedding quota

- Fixed Git commit: `4081a4a9`
- Intended scope: Development only, 19 tasks, frozen 186-chunk passage manifest
- Model calls: 0
- Validation/holdout use: 0
- Status: no valid R23 trace produced

The first operational attempt, run
`drawiohydrationpdfresearch_3b032e5da9e84c2a92a153fcaeab3389`, omitted the frozen
`MATERIAL_RAG_TOKENIZER_PATH`. It produced 283 chunks and passage hash
`8e34642d77b16e171a1893e4e2c28fa4ced921309fa223c27dde9f8067b6a539`, rather than the
preregistered 186 chunks and passage hash
`28f45bff47cd1d388407a156c51ad2e82fb5dfcb07ad85d373779dffb05617b9`. The attempt was
rejected before inspecting any R23 gate result. Its temporary vectors were confirmed deleted.

The corrected attempts explicitly bound the production tokenizer SHA-256
`62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626`,
`e1-v5`, `flat-leaf-v1`, and the Development split. Runs
`drawiohydrationpdfresearch_ec1a060e5f5d4011926e7d068bbf2927` and
`drawiohydrationpdfresearch_d78357d8f9eb4bc880498935c424bbc3` both reconstructed the
correct 186 chunks, but Pinecone returned HTTP 429 for the first passage-embedding batch through
all five frozen retries. Neither attempt reached upsert, search, task evaluation, or trace export;
their `finally` cleanup completed.

A one-input quota probe then returned `RESOURCE_EXHAUSTED` with the explicit reason that the
organization had reached the current-month 5,000,000-token limit for
`multilingual-e5-large`. This is an external quota block, not an R23 gate failure. R23 remains
unscored. Do not substitute another embedding model, reuse a post-hoc lane, open R24, or inspect
Validation/holdout. Resume the same frozen R23 only after the Pinecone monthly quota resets or the
plan is upgraded.
