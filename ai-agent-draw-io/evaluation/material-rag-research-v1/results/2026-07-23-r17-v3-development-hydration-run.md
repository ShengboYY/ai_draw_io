# R17 v3 Development hydration diagnostic

- Git commit: `b419a70a`
- Split: `development`
- Query retries: two empty responses recovered
- Pinecone namespace: isolated Development namespace; run vectors confirmed deleted
- Trace: `2026-07-23-r17-v3-development-hydration-trace.json`
- Model calls: 0

The complete publisher source/page registry was active. The scoped-pool gate stopped at `dgt-dev-20`: its
selected source returned 28 candidates while the manifest declared 35 projected chunks. Seven of those chunks
are lexical-only and are never upserted to Pinecone, so 28 is the complete dense vector pool.

R18 records per-source counts after applying the same `DENSE_AND_LEXICAL` filter as the actual upsert. This
trace remains diagnostic and is not used for generation.
