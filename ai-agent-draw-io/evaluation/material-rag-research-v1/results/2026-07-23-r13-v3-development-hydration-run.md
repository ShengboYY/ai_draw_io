# R13 v3 Development hydration diagnostic

- Git commit: `5c24c10a13d4f41a584c96e270022eba45f6f88f`
- Split: `development`
- Canonical/chunk profiles: `e1-v5` / `flat-leaf-v1`
- Retrieval-required tasks: 19; no-retrieval tasks: 1
- Indexed chunks: 186
- Embedding tokens: p50 105, p95 338, max 380
- Pinecone namespace: isolated Development namespace; run vectors confirmed deleted
- Trace: `2026-07-23-r13-v3-development-hydration-trace.json`
- Model calls: 0

The live PDFBox → OCR → canonical evidence → chunk → Pinecone top-40 run passed. The paired exporter then
failed closed before prompt construction because `dgt-dev-10` did not receive its required planning-scan
visual artifact in candidate top-8. The correct page-5 artifact was present at raw rank 19. A local,
gold-independent distinct-artifact selector probe exposed the next failure at `dgt-dev-12`: its datacenter
page-3 visual artifact was absent from top-40 because the 15% visual-page budget selected only two pages from
the seven-page document.

This artifact is diagnostic only. R14 preregisters bounded source-independent visual-page coverage and requires
a fresh trace from the new frozen commit before any generation call.
