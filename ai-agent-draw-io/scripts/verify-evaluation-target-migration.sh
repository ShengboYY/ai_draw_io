#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose -f "$ROOT_DIR/docker-compose.yml")
SCHEMA=eval_target_migration_contract

mysql_cli() {
  "${COMPOSE[@]}" exec -T db sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$@"' sh "$@"
}

cleanup() {
  mysql_cli -e "DROP DATABASE IF EXISTS $SCHEMA" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Use an isolated schema but execute the exact production migration file.
"${COMPOSE[@]}" up -d db >/dev/null
for _ in {1..30}; do
  if "${COMPOSE[@]}" exec -T db sh -c 'mysqladmin ping -uroot -p"$MYSQL_ROOT_PASSWORD" --silent' >/dev/null 2>&1; then break; fi
  sleep 1
done
mysql_cli < "$ROOT_DIR/docs/sql/test-fixtures/evaluation-target-migration.sql"
mysql_cli "$SCHEMA" < "$ROOT_DIR/docs/sql/migrations/2026-07-13-add-evaluation-targets.sql"

actual="$(mysql_cli --batch --skip-column-names "$SCHEMA" -e "
SELECT id, COALESCE(evaluation_target, 'NULL'), target_migration_status
FROM eval_case_working_copy ORDER BY id;
SELECT 'CASE', evaluation_target, target_migration_status FROM eval_case_version WHERE case_id='quality';
SELECT 'DATASET', evaluation_target, '' FROM eval_dataset WHERE id='drawing-core';
SELECT 'VERSION', evaluation_target, '' FROM eval_dataset_version WHERE dataset_id='drawing-core' AND version='v1';
SELECT 'RUN', evaluation_target, '' FROM eval_run WHERE id='drawing-run';
SELECT 'REPORT', COUNT(*), SUM(target_migration_status='AMBIGUOUS') FROM eval_target_migration_report;")"

expected=$'ambiguous\tNULL\tAMBIGUOUS\ncritical\tDRAWING_QUALITY\tINFERRED\nexplicit\tFULL_AGENT\tCONFIRMED\nfixture\tFULL_AGENT\tINFERRED\nmajor\tDRAWING_QUALITY\tINFERRED\nquality\tDRAWING_QUALITY\tINFERRED\nrouter\tINTENT_ROUTER\tINFERRED\nCASE\tDRAWING_QUALITY\tINFERRED\nDATASET\tDRAWING_QUALITY\t\nVERSION\tDRAWING_QUALITY\t\nRUN\tDRAWING_QUALITY\t\nREPORT\t8\t1'

if [[ "$actual" != "$expected" ]]; then
  echo "Evaluation target migration contract failed." >&2
  diff -u <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") >&2 || true
  exit 1
fi

echo "Evaluation target migration contract passed."
