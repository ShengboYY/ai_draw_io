# E7 r10 embedding-input manifest — pre-registration

- Date: 2026-07-23
- Status: local trace-provenance implementation complete; no Pinecone, model provider, Validation, or holdout run.

## Purpose

R8 and R9 used the same `dgt-dev-02` request and the same architecture page-3 VISUAL chunk ID, while R9 removed
18 dense page-parent vectors. The observed visual rank nevertheless changed from 24 to 28. Those historical traces
do not contain a stable fingerprint of the exact passage or query inputs passed to the embedding endpoint, so that
difference cannot be attributed solely to page-parent routing.

## Single intervention

Every future retrieval result and Development hydration trace records an `embeddingInputManifest` with the provider,
model, vector dimension, SHA-256 of ordered passage inputs, and SHA-256 of ordered task-query inputs. The hashes use
source version, chunk ID and embedding-text hash for passages, and task ID plus query text for queries. It does not
record vectors, credentials, evaluator anchors, expected answers, XML assertions, or model outputs.

## Gate

Before another visual/OCR retrieval-quality intervention is interpreted, its trace must contain this manifest. A
repeated arm may be compared only when model identity and both input hashes match; otherwise it is a provenance
diagnostic, not evidence of a ranking effect.
