# Production Database Migration Releases

This directory packages reviewed, ordered migration releases added after the successful 2026-07-05 production database initialization.

## Releases

- `Dockerfile` / `release-20260717.manifest`: the original 30-migration release.
- `Dockerfile.20260722` / `release-20260722.manifest`: the five additive multimodal migrations from anonymous workspace credentials through WP3B document-processing artifacts. Its dedicated runner refuses to execute any DDL until all 30 `20260717` history rows and their packaged checksums are verified.
- `Dockerfile.20260729` / `release-20260729.manifest`: the ordered M1 durable turn lifecycle release. It requires all five `20260722` history rows, then applies conversation/turn control, pinned input recovery, and redacted lifecycle trace tables.
- `Dockerfile.20260730` / `release-20260730.manifest`: the M5 source-aware strong commit release. It requires all three `20260729` history rows, then adds exact execution/source bindings, durable clarification authority, Direct visual provenance, and source usage pins.
- `Dockerfile.20260731` / `release-20260731.manifest`: the confirmed Memory v1 release after `20260730`. It requires the single `20260730` history row, then creates user-confirmable Memory candidate and materialized-memory storage.
- `Dockerfile.20260801` / `release-20260801.manifest`: the ordered compatibility release after `20260731`. It requires the single `20260731` history row, then migrates canonical conversation scopes and creates Chartbook Profile storage.
- `Dockerfile.20260813` / `release-20260813.manifest`: the durable source-preparation release after `20260801`. It creates request source snapshots and the Direct visual preparation hand-off used by source-aware takeover recovery.
- `Dockerfile.20260814` / `release-20260814.manifest`: the unified Auto Memory release after `20260813`. It creates tenant-fenced USER/CHARTBOOK Memory and bounded evidence storage, then migrates materialized v1 Chartbook Memory without deleting the legacy tables.

## Safety properties

- The manifest records dependency order explicitly.
- The image contains the SQL; it does not download scripts at runtime.
- The minimal Alpine image contains the MariaDB client and its MySQL 8 authentication plugin rather than a full MySQL server.
- The runner requires TLS, verifies the RDS endpoint against the checksum-pinned AWS global CA bundle, only accepts the `ai_draw_io` database, and does not put the password in command arguments.
- `deployment_schema_history` stores each filename and SHA-256 checksum.
- A previously applied file is skipped only when its checksum still matches.
- Any SQL error, missing file, checksum change, or unexpected history count stops the task with a non-zero exit code.

MySQL DDL is not fully transactional. If a file fails after partially applying DDL, do not blindly rerun it. Inspect the schema and logs, then use a reviewed forward fix or restore the pre-migration snapshot.

## Local validation

Reproduce the appropriate pre-release schema in a disposable MySQL 8.4 database, then build and run the selected release image against it. A successful `20260722` run must report:

```text
Migration release 20260722 is complete: 5 recorded, 5 applied in this run.
```

Run the same image a second time against that database. It must verify every checksum and report `0 applied in this run`.

`MYSQL_SSL_VERIFY_SERVER_CERT=false` is permitted only for this disposable local test. Do not set it in an ECS task definition; production uses the checksum-pinned AWS RDS CA bundle and endpoint verification by default.

A successful `20260729` run must report:

```text
Migration release 20260729 is complete: 3 recorded, 3 applied in this run.
```

A successful `20260730` run must report:

```text
Migration release 20260730 is complete: 1 recorded, 1 applied in this run.
```

A successful `20260731` run must report:

```text
Migration release 20260731 is complete: 1 recorded, 1 applied in this run.
```

A successful `20260801` run must report:

```text
Migration release 20260801 is complete: 2 recorded, 2 applied in this run.
```

A successful `20260813` run must report:

```text
Migration release 20260813 is complete: 4 recorded, 4 applied in this run.
```

A successful `20260814` run must report:

```text
Migration release 20260814 is complete: 1 recorded, 1 applied in this run.
```

Run the same image a second time against that database. It must verify the packaged checksums and report `0 applied in this run`.

## Production sequence

1. Select exactly one release and verify its precondition: `20260717` requires the 2026-07-05 schema baseline; `20260722` requires all 30 `20260717` history rows and checksums; `20260729` requires all five `20260722` history rows and checksums; `20260730` requires all three `20260729` history rows and checksums; `20260801` requires the `20260731` history row and checksum; `20260813` requires both `20260801` history rows and checksums; `20260814` requires all four `20260813` history rows and checksums. The selected runner enforces its predecessor gate before DDL.
2. Confirm RDS automated backups and point-in-time recovery are available.
3. Create a manual RDS snapshot and wait until its status is `available`.
4. Run the selected release image once as an ECS Fargate one-off task in the same VPC as RDS.
5. Inject `MYSQL_PASSWORD` from Secrets Manager; never pass or print its value through GitHub.
6. Require the task's essential container exit code to be `0`.
7. Review the dedicated CloudWatch migration log stream.
8. Verify `deployment_schema_history`, key tables, and application data.
9. Deploy the backend with the `migration-completed` database gate. Keep `TURN_ENGINE_LIFECYCLE_ENABLED=false` and the startup target empty until the source-aware V2 execution and ingress gates are approved; then deploy the frontend.

Do not start the ECS service deployment if the migration task fails.

The lifecycle composition also has an explicit, one-shot startup migration hook:

- Keep `TURN_ENGINE_MIGRATION_STARTUP_TARGET_MODE` empty for ordinary starts.
- Only set it while operating exactly one instance that already holds the singleton lock and has passed orphan repair; the runner performs the durable backfill, expiry scan, and compare-and-switch under the admission drain.
- An invalid target or rejected durable transition fails startup instead of serving with a partially applied migration.
- M1 deliberately rejects `V2_CANARY` until stable cohort selection and the later all-path gates exist; this hook does not bypass that restriction.
