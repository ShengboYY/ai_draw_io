# R23 v3 Development dense-union lexical stabilization result

- Git commit: `4081a4a9`
- Run ID: `drawiohydrationpdfresearch_b66e8ef83edf4367883b330a683d1a56`
- Candidate mode: `original-evidence-lexical-stabilized-v1`
- Chunks: 186; tasks: 19/19 retrieval tasks plus the frozen no-retrieval task
- Passage SHA-256: `28f45bff47cd1d388407a156c51ad2e82fb5dfcb07ad85d373779dffb05617b9`
- Query SHA-256: `e9121372eeacff2fee5f2d298796d0dead65f7aeef608af23b5e5faa38f7a8ce`
- Pinecone vectors: confirmed deleted
- Model calls: 0

The Pinecone credit probe succeeded before this run. The frozen tokenizer, `e1-v5`,
`flat-leaf-v1`, Development split, passages, and queries matched R22/R23a. One rewritten query
returned an empty first response and recovered under the frozen retry policy.

The preregistered gate result was:

- raw top-40 required-evidence completeness: **17/19**
- source-aware top-8 candidate completeness: **16/19**
- declared visual/OCR artifact coverage: **7/7**
- selector changed tasks: **12/19** (63.16%)

Raw top-40 missed `dgt-dev-07` and `dgt-dev-08`. Their required workflow chunks were present in
both provider lanes, but lexical stabilization moved them outside the final top-40:

- `dgt-dev-07`: matching chunks had original/rewritten ranks 28/33, 48/58, and 26/30; none
  survived the stabilized top-40.
- `dgt-dev-08`: the shared required chunk ranked 26/30 and did not survive the stabilized top-40.

The final candidate additionally missed `dgt-dev-16`: its `pre-objstore` chunk was present at
original rank 4, rewritten rank 11, and stabilized rank 24, but the unchanged selector did not
promote it into the model-visible top-8.

R23 therefore does not advance. This result satisfies the preregistered stop condition: retrieval
tuning ends, no R24 is opened, no generation prompt is built, and no model or Validation/holdout
request is allowed from this result. The residual failures are classified as two lexical
stabilization losses and one selector loss.
