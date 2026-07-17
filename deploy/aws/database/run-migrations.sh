#!/bin/sh
set -eu

: "${MYSQL_HOST:?MYSQL_HOST is required}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
: "${MYSQL_USER:?MYSQL_USER is required}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"

MYSQL_PORT="${MYSQL_PORT:-3306}"
MIGRATION_RELEASE="20260717"
MIGRATION_ROOT="/migration"
MIGRATION_MANIFEST="${MIGRATION_ROOT}/release-20260717.manifest"
EXPECTED_MIGRATION_COUNT="30"

if [ "$MYSQL_DATABASE" != "ai_draw_io" ]; then
  echo "Refusing to migrate unexpected database: ${MYSQL_DATABASE}" >&2
  exit 1
fi

# MYSQL_PWD prevents the password from appearing in process arguments and task logs.
export MYSQL_PWD="$MYSQL_PASSWORD"

mysql_command() {
  mysql \
    --protocol=TCP \
    --host="$MYSQL_HOST" \
    --port="$MYSQL_PORT" \
    --user="$MYSQL_USER" \
    --ssl-mode=REQUIRED \
    --batch \
    --skip-column-names \
    "$@"
}

mysql_command "$MYSQL_DATABASE" <<'SQL'
CREATE TABLE IF NOT EXISTS deployment_schema_history (
  script_name VARCHAR(255) PRIMARY KEY,
  checksum_sha256 CHAR(64) NOT NULL,
  release_id VARCHAR(64) NOT NULL,
  execution_ms BIGINT NOT NULL,
  applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_deployment_schema_history_release (release_id, applied_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
SQL

applied_this_run=0

while IFS= read -r file || [ -n "$file" ]; do
  case "$file" in
    ''|'#'*) continue ;;
  esac

  migration_path="${MIGRATION_ROOT}/sql/${file}"
  if [ ! -f "$migration_path" ]; then
    echo "Migration file is missing: ${file}" >&2
    exit 1
  fi

  checksum="$(sha256sum "$migration_path" | awk '{print $1}')"
  recorded_checksum="$(mysql_command "$MYSQL_DATABASE" --execute="SELECT checksum_sha256 FROM deployment_schema_history WHERE script_name='${file}' LIMIT 1;")"

  if [ -n "$recorded_checksum" ]; then
    if [ "$recorded_checksum" != "$checksum" ]; then
      echo "Checksum mismatch for already-applied migration: ${file}" >&2
      exit 1
    fi
    echo "Skipping already-applied migration: ${file}"
    continue
  fi

  echo "Applying migration: ${file}"
  started_at="$(date +%s)"
  mysql_command "$MYSQL_DATABASE" < "$migration_path"
  finished_at="$(date +%s)"
  execution_ms="$(((finished_at - started_at) * 1000))"

  mysql_command "$MYSQL_DATABASE" --execute="INSERT INTO deployment_schema_history (script_name, checksum_sha256, release_id, execution_ms) VALUES ('${file}', '${checksum}', '${MIGRATION_RELEASE}', ${execution_ms});"
  applied_this_run="$((applied_this_run + 1))"
done < "$MIGRATION_MANIFEST"

release_count="$(mysql_command "$MYSQL_DATABASE" --execute="SELECT COUNT(*) FROM deployment_schema_history WHERE release_id='${MIGRATION_RELEASE}';")"
if [ "$release_count" -ne "$EXPECTED_MIGRATION_COUNT" ]; then
  echo "Migration history count is ${release_count}; expected ${EXPECTED_MIGRATION_COUNT}." >&2
  exit 1
fi

echo "Migration release ${MIGRATION_RELEASE} is complete: ${release_count} recorded, ${applied_this_run} applied in this run."
