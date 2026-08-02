# FreeDraw System Architecture Figure Brief

## Figure goal

Explain how FreeDraw's deployed frontend, backend modules, AI execution path, document ingestion path, and storage systems connect today, then show a recommended target architecture that improves recovery, scaling, and failure isolation.

## Paper claim

FreeDraw should evolve from a single backend process that owns HTTP streaming and long-running AI work into a durable workflow architecture with explicit control, execution, knowledge, quality, and data planes.

## Figure type and mode

- Figure type: `system-architecture`
- Mode: `image`
- Language: concise Chinese labels with standard English technical terms preserved

## Panels

### A. Current architecture

- User -> Cloudflare -> ALB.
- ALB routes `/` to Next.js ECS and `/api/v1/*` to Spring Boot ECS.
- Next.js integrates Draw.io and consumes REST/NDJSON.
- Spring Boot technical modules connect as Trigger/API -> Application -> Domain -> Infrastructure.
- The backend process owns Turn admission, local execution, streaming, canvas commit, RAG, visual review, telemetry, and admin/evaluation.
- MySQL is the authoritative metadata and latest-canvas store.
- S3 stores material artifacts; Pinecone stores vector projections.
- The ingestion worker polls durable MySQL jobs and writes MySQL, S3, and Pinecone.
- LLM/VLM and SES are external providers.

### B. Recommended target architecture

- Web API owns authentication, CRUD, Turn admission, status queries, and resumable SSE only.
- MySQL owns the Turn ledger, append-only event log, outbox, job queue, diagram metadata, and immutable revision metadata.
- Turn workers claim durable attempts and execute Context -> RAG -> Agent -> Canvas validation -> atomic revision commit.
- Render/review workers consume revision events, render canonical PNG, run deterministic checks and VLM review, and may enqueue at most one bounded repair proposal.
- The ingestion worker remains independently scalable.
- S3 stores immutable canvas XML/PNG/material artifacts; Pinecone stores retrieval projections.
- Redis is optional for Spring Session, ephemeral pub/sub acceleration, and distributed rate limiting; correctness must not depend on Redis.

## Must-keep labels

- Next.js + Draw.io
- Spring Boot
- Turn Admission
- Turn Ledger
- Turn Worker
- Context / RAG
- Agent Runtime
- Canvas Engine
- Immutable Revision
- Render / Review Worker
- MySQL
- S3
- Pinecone
- LLM / VLM
- REST / NDJSON
- Resumable SSE

## Data

Not applicable. No fabricated performance or capacity values.

## Style constraints

- White background, publication-style vector diagram.
- Blue for synchronous product flow.
- Green dashed arrows for asynchronous/event flow.
- Purple for AI/model execution.
- Gold for quality/review.
- Gray for infrastructure and external systems.
- Explicit module boundaries and left-to-right reading order.
- Avoid decorative icons, gradients, shadows, and paragraph-length labels.

## Output formats

- DOT source
- Editable SVG
- PNG preview

## Verification checklist

- Current and target architectures are not mixed.
- Every arrow has an unambiguous direction.
- Browser, API, execution, storage, and worker ownership are clear.
- MySQL remains the correctness source of truth.
- Redis is marked optional and not authoritative.
- No unsupported throughput, latency, or cost claims appear.
