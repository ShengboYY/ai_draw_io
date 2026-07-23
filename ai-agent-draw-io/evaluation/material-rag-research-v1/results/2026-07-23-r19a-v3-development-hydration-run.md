# R19a v3 Development hydration result

- Git commit: `a4924c84f708bbe361d594e7e5dc867f646410f4`
- Split: `development`
- Frozen tokenizer SHA-256: `62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626`
- Chunks: 186; embedding tokens p50=105, p95=338, max=380
- Scoped pools: 18 chartbook-auto tasks at 40/40; selected-only task at 28/28
- Raw top-40 publisher canonical availability: 19/19
- Pinecone namespace: isolated Development test namespace; run vectors confirmed deleted
- Model calls: 0

The first attempted run omitted the frozen tokenizer and silently used the fixture-only codepoint fallback,
producing 283 chunks. It was rejected as a confounded diagnostic and its vectors were deleted. The formal rerun
used the frozen tokenizer and reproduced the R18 passage/query hashes and indexed-vector counts.

The existing selector did not advance: raw top-8 control was complete for 13/19 retrieval tasks and the
candidate for 12/19. The model-visible readiness gate therefore failed before prompt construction. R19b is
preregistered as a local selector-only comparison on this frozen trace.
