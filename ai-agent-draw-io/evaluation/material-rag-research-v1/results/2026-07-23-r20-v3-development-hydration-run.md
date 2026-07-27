# R20 v3 Development hydration result — intervention wiring failure

- Git commit: `52c6ade9043d583f1048714dbbb88b0087bba470`
- Split: `development`
- Chunks: 186; embedding tokens p50=105, p95=338, max=380
- Scoped pools: 18 chartbook-auto tasks at 40/40; selected-only task at 28/28
- Query retries: one original and one rewritten empty response recovered
- Pinecone namespace: isolated Development test namespace; run vectors confirmed deleted
- Model calls: 0

The emitted trace had raw canonical availability of 16/19. Control was complete for 3/19 tasks and candidate
for 15/19, so the model-visible gate failed.

This run does not measure the bilingual R20 intervention. The rewritten query was embedded and requested, as
shown by the changed query-input hash, but `writeTaskHydrationTrace` serialized the original-query
`PostprocessMode.RANKED_RAW` lane. The rewritten candidates remained in `QueryMode.EVIDENCE_FOCUSED` and never
entered the trace. R21 is preregistered as a lane-wiring-only repair; no query terms or selector policy may
change in that run.
