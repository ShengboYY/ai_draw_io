# Production Database Migration Release 20260717

This directory packages the 30 migrations added after the successful 2026-07-05 production database initialization.

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

Reproduce the old production schema in a disposable MySQL 8.4 database, then build and run this image against it. A successful run must report:

```text
Migration release 20260717 is complete: 30 recorded, 30 applied in this run.
```

Run the same image a second time against that database. It must verify every checksum and report `0 applied in this run`.

`MYSQL_SSL_VERIFY_SERVER_CERT=false` is permitted only for this disposable local test. Do not set it in an ECS task definition; production uses the checksum-pinned AWS RDS CA bundle and endpoint verification by default.

## Production sequence

1. Confirm the production schema still matches the 2026-07-05 baseline.
2. Confirm RDS automated backups and point-in-time recovery are available.
3. Create a manual RDS snapshot and wait until its status is `available`.
4. Run this image once as an ECS Fargate one-off task in the same VPC as RDS.
5. Inject `MYSQL_PASSWORD` from Secrets Manager; never pass or print its value through GitHub.
6. Require the task's essential container exit code to be `0`.
7. Review the dedicated CloudWatch migration log stream.
8. Verify `deployment_schema_history`, key tables, and application data.
9. Deploy the backend with the `migration-completed` database gate, then deploy the frontend.

Do not start the ECS service deployment if the migration task fails.
