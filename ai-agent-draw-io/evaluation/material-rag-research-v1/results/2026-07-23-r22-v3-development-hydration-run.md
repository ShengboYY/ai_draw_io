# R22 v3 Development original/rewrite RRF result

- Git commit: `9b68c322ed1c12e804f07315d2967a478d719da6`
- Run ID: `drawiohydrationpdfresearch_f569b3d78220405797f67a3a3ddb2dba`
- Candidate query mode: `original-evidence-rrf-v1`
- Query fusion: `equal-rrf-v1:k60:original1.0:rewritten1.0`
- Chunks: 186; scoped pools: 18 tasks at 40/40 and selected-only at 28/28
- Pinecone vectors: confirmed deleted
- Model calls: 0

R22 improved raw top-40 canonical availability from R21's 14/19 to 17/19. The source-aware top-8 candidate
was complete for 16/19 tasks, versus 13/19 in R21; control was complete for 4/19. The candidate changed
14/19 retrieval-required contexts (73.68%), and all 7 declared visual/OCR artifact tasks passed.

The preregistered 19/19 raw and candidate gates were not met, so R22 does not advance and no prompt or model
run is permitted. `dgt-dev-01` and `dgt-dev-19` lacked their required publisher identities in fused top-40.
`dgt-dev-17` contained all three required incident identities at fused rank 28, but the frozen selector did
not promote them into top-8.

The next safe step is diagnostic instrumentation, not another rank-policy guess: record the original and
rewritten top-80 rank lineage alongside the unchanged fused output, then use one authorized Development
diagnostic trace to determine whether the two raw misses were caused by lane retrieval or RRF truncation.
