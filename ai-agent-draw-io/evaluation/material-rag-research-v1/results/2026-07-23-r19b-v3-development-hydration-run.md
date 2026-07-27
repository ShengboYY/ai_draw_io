# R19b v3 Development hydration and paired-gate result

- Git commit: `b26c0682ba7fce191f7ccf6b4f36604a7a5c9d1a`
- Split: `development`
- Chunks: 186; embedding tokens p50=105, p95=338, max=380
- Scoped pools: 18 chartbook-auto tasks at 40/40; selected-only task at 28/28
- Query retry: one original-query empty response recovered
- Pinecone namespace: isolated Development test namespace; run vectors confirmed deleted
- Selector: `publisher-identity-lexical-reservation-top8-v1`
- Changed tasks: 15/19 (78.95%)
- Model calls: 0

The formal model-visible gate failed. Raw top-8 control was complete for 3/19 retrieval tasks and candidate
for 16/19. Candidate missed `dgt-dev-13`, `dgt-dev-16` and `dgt-dev-17`. The latter two had all canonical
evidence in raw top-40; `dgt-dev-13` did not retrieve `dcc-threshold` in top-40, leaving raw canonical
availability at 18/19.

No prompt was built and Validation remained closed. R20 is preregistered as a query-only bilingual Draw.io
term expansion; the failed R19b artifacts remain diagnostic evidence.
