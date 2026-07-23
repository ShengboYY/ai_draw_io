# R21 v3 Development hydration and paired-gate result

- Git commit: `c08edce31f2559fce8769a013bc636d453eea606`
- Candidate query mode: `evidence-focused-v1`
- Query rewrite: `drawio-bilingual-evidence-focused-v2:han-aware-prefix:frozen-domain-terms`
- Chunks: 186; scoped pools: 18 tasks at 40/40 and selected-only at 28/28
- Pinecone vectors: confirmed deleted
- Model calls: 0

The lane-wiring and provenance repair worked, but the candidate input gate failed. Raw top-40 canonical
availability was 14/19. Raw top-8 control was complete for 3/19 retrieval tasks and candidate for 13/19.

R21 therefore does not advance. R22 is preregistered to fuse the already-requested original and rewritten
top-80 lists with deterministic equal-weight reciprocal-rank fusion. It does not add provider requests or use
task target sources or evaluator data.
