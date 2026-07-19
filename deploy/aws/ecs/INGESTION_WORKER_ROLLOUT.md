# Ingestion Worker profile rollout

Use a two-revision rollout. The checked-in task-definition example intentionally keeps
`MATERIAL_DOCUMENT_PROCESSING_ENABLED=false`; do not enable it in the first revision.

## 1. Deploy and verify the image with processing disabled

1. Push the reviewed `linux/amd64` Worker image by immutable release SHA.
2. Register a task definition based on `ingestion-worker-task-definition.example.json` with the new image and `MATERIAL_DOCUMENT_PROCESSING_ENABLED=false`.
3. Start it as a separately named profile service. Do not update or scale down the previous-profile service.
4. Confirm the task is healthy and its image digest matches the reviewed digest.
5. Run the image checks `tesseract --version` and `tesseract --list-langs`; the declared runtime and `eng+chi_sim` are also verified by Worker startup when processing is enabled.

## 2. Enable document processing in a second task revision

Only after step 1 succeeds, register a second revision that changes
`MATERIAL_DOCUMENT_PROCESSING_ENABLED` to `true`. Update only the new-profile service to this
revision. Keep the previous-profile service at its existing desired count.

The current image declares:

```text
TESSERACT_RUNTIME_VERSION=tesseract-4.1.1
TESSERACT_LANGUAGES=eng+chi_sim
```

Do not override these values in ECS. A future image must update its package pins and both image
environment values together.

## 3. Drain the previous profile before scale-down

Obtain the previous profile fingerprint from the old task logs or the corresponding
`material_processing_revision.fingerprint`, then run this read-only gate against RDS:

```sql
WITH profile_jobs AS (
    SELECT j.id, j.status, r.fingerprint
    FROM material_processing_job j
    JOIN material_processing_revision r ON r.id = j.revision_id
    WHERE j.revision_id IS NOT NULL
    UNION ALL
    SELECT j.id, j.status, r.fingerprint
    FROM material_processing_job j
    JOIN material_upload_session u ON u.id = j.upload_session_id
    JOIN material_processing_revision r ON r.id = u.processing_revision_id
    WHERE j.upload_session_id IS NOT NULL
)
SELECT status, COUNT(*) AS job_count
FROM profile_jobs
WHERE fingerprint = :previous_profile_fingerprint
  AND status IN ('QUEUED', 'RUNNING', 'RETRY')
GROUP BY status;
```

Keep the previous-profile service running while this query returns any row. Require two empty
checks separated by at least the Worker lease duration (30 minutes), then confirm no expired lease
was requeued before scaling the old service to zero. Preserve its task definition and image until
the associated processing revisions have published or failed terminally.

If the new profile fails, scale its separate service to zero. The old service remains available for
its pinned work and ordinary text-input drawing remains independent of both services.

## Current execution boundary

The repository artifacts and local `linux/amd64` images were verified without an AWS profile. No
production RDS migration task, ECR push, ECS registration/service update, or production flag change
is represented as completed by this runbook. Those operations require the target AWS account,
credentials, resource names, snapshot confirmation, and change approval.
