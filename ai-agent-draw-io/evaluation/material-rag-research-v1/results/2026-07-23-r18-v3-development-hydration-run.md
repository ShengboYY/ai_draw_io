# R18 v3 Development hydration and paired-gate result

- Git commit: `ce917431`
- Split: `development`
- Scoped pools: 18 chartbook-auto tasks at 40/40; one selected-only task at 28/28
- Query retry: one rewritten empty response recovered
- Pinecone namespace: isolated Development namespace; run vectors confirmed deleted
- Trace: `2026-07-23-r18-v3-development-hydration-trace.json`
- Paired hydration: `2026-07-23-r18-v3-development-paired-hydration.json`
- Model calls: 0

Passed:

- exact task coverage and scoped candidate-pool completeness;
- Git/corpus-lock/embedding/source-identity provenance;
- source scope and artifact path/SHA validation;
- paired effectiveness: 18/19 retrieval tasks changed (94.74%).

Failed:

- candidate required-evidence readiness: 2/19 complete (`dgt-dev-03`, `dgt-dev-11`);
- control required-evidence readiness: 4/19 complete (`dgt-dev-02`, `dgt-dev-05`,
  `dgt-dev-10`, `dgt-dev-11`);
- five tasks lack complete publisher canonical identity even in raw top-40:
  `dgt-dev-07`, `dgt-dev-08`, `dgt-dev-12`, `dgt-dev-14`, `dgt-dev-15`.

The candidate selector is rejected. R19 separates publisher identity availability from relevance-preserving
artifact-aware context selection. No prompts or model requests were created.
