# R23a v3 Development query-rank lineage diagnostic

- Git commit: `31b4d99a`
- Run ID: `drawiohydrationpdfresearch_1cf1e7ccc4754dde92b99348d83034c2`
- Query-rank lineage: `original-rewrite-top80-fused-ranks-v1`
- Chunks: 186; tasks: 19/19
- Pinecone vectors: confirmed deleted
- Model calls: 0

R23a is diagnostic only and does not replace or re-score R22. The passage and query input hashes exactly
match R22. One rewritten query and one original query returned transient empty responses and recovered under
the frozen retry policy.

The two R22 raw misses were present in both provider lanes during R23a:

- `dgt-dev-01`: the shared chunk for `dwh-sequence-type` and `dwh-handoff-type` ranked original 15,
  rewritten 19, and fused 7.
- `dgt-dev-19`: the shared chunk for `daa-latest` and `daa-version-pin` ranked original 26, rewritten 17,
  and fused 17.

`dgt-dev-17` also had its shared severity chunk in both lanes at original 16 and rewritten 45, producing
fused rank 21. The existing top-8 selector did not promote it.

Despite identical input manifests, R22 and R23a fused top-40 overlap ranged from 24 to 35 chunks per task
(median 29, mean 28.63), and none of the 19 top-8 heads were identical. R23a's candidate-complete tasks
remained 16/19 but the missing task IDs changed to `dgt-dev-07`, `dgt-dev-16`, and `dgt-dev-17`. This result
supports provider/ranking instability as the dominant cause; it does not support another query-weight guess.

The next intervention should be the final retrieval experiment: deterministically lexical-rerank the complete
union of the already-returned original/rewrite top-80 lanes, without additional Pinecone requests. If that
single R23 Development run still misses the frozen gate, retrieval tuning stops and the residual failures are
reported rather than opening another tuning chain.
