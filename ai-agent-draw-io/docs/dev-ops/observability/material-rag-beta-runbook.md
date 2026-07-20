# Material RAG Beta runbook

## Preconditions

- Apply every material migration through `2026-08-09-add-material-operations-indexes.sql` in order.
- Deploy the API, ingestion Worker and ClamAV separately. Keep the Worker processing fingerprint,
  Tesseract runtime and `eng+chi_sim` OCR configuration pinned.
- Import `material-rag-alerts.yml` and restrict both Prometheus and the admin capability endpoint to
  operators.
- Keep every material flag disabled until the corresponding dependency check below passes.

## Staged rollout

1. Enable Worker ingestion for internal synthetic fixtures and set
   `MATERIAL_INGESTION_ROLLOUT_ENABLED=true`.
2. Enable registered upload and the资料库/catalog. Confirm scan-before-preview/model/vector behavior.
3. Enable chartbook/scope use, then `MATERIAL_RETRIEVAL_SHADOW_ENABLED=true`. Shadow mode runs the
   bounded retrieval pipeline but must store
   only opaque candidate/evaluation metrics and must not add citations or evidence prompts to traces.
4. After shadow evaluation, enable `MATERIAL_RAG_ENABLED=true` for explicit registered requests.
5. Enable `MATERIAL_CITATION_COMMIT_ENABLED=true`, then
   `MATERIAL_EVIDENCE_ANSWER_ENABLED=true` after their atomic-commit smoke tests pass.
6. Run the 165-case locked RAG release dataset, security corpus and fault matrix. Include the immutable
   report/dataset/processing/ranking/model/deployment profile IDs and the no-answer calibration slices,
   then submit `rag-release-report-v1` to the Release Owner gate.
7. Set `MATERIAL_RELEASE_APPROVED=true` and set `MATERIAL_RELEASE_REPORT_VERSION` to the exact
   `rag-approval-v1:<sha256>` returned by the passing gate.
8. Enable `MATERIAL_UPLOAD_ANONYMOUS_ENABLED=true` last. The capacity breaker rejects new anonymous
   uploads at 95% and anonymous reprocessing at 85%; registered and ordinary text drawing remain
   available.

## Capability and alert checks

- `GET /api/v1/admin/material-capabilities` must show `PLAIN_TEXT_DRAWING=AVAILABLE` even when all
  material capabilities are disabled or degraded.
- Queue oldest age above 120 seconds, expired leases and stuck deletion counts require investigation.
- Capacity is the maximum of monthly indexed pages and live Pinecone embedding/RU/WU usage. The
  authenticated billing exporter must `POST /api/v1/admin/material-capabilities/capacity` at least once
  per `MATERIAL_PROVIDER_CAPACITY_MAX_AGE_SECONDS` with its provider capture time, monotonically
  increasing sequence, the three percentages and dependency health. Configure a separate 32+ character
  `MATERIAL_PROVIDER_CAPACITY_EXPORTER_SECRET` and send it in `X-Material-Capacity-Token`. The shared
  MySQL singleton rejects delayed lower-sequence retries, so all API tasks read the same state. Missing
  or stale samples fail closed for anonymous work.
- Existing projection reconciliation repairs missing vectors and removes recorded provider orphans.
  Worker/deletion lease reapers and lifecycle maintenance reconcile expired jobs, deletion work and
  read leases. Do not run manual deletes outside these fenced workflows.

## Required smoke tests

- Upload an EICAR fixture and confirm it never reaches preview, model context or Pinecone.
- Stop Pinecone and the Worker; ordinary text drawing must still succeed.
- Force Pinecone 429/5xx and confirm lexical degradation or an explicit evidence-unavailable result.
- Stop the capacity exporter beyond its maximum age and confirm anonymous upload is blocked while
  registered and plain-text drawing still work.
- Kill a Worker while holding a processing lease; verify the job is requeued with a new fence.
- Interrupt S3 deletion and induce a MySQL deadlock; verify durable retry and tombstone privacy.
- In real diagrams.net, verify selection/highlight, answer/citation atomic commit and unchanged canvas
  version/hash for evidence answers.

## Rollback

Disable anonymous upload first, followed by evidence answer, citation commit and RAG. Do not roll back
by dropping additive schema or deleting material data. Stop Worker polling if ingestion is unsafe;
queued work remains durable. Existing opaque Draw.io properties remain render-safe while citation
features are disabled.
