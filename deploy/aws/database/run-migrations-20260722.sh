#!/bin/sh
set -eu

: "${MYSQL_HOST:?MYSQL_HOST is required}"
: "${MYSQL_DATABASE:?MYSQL_DATABASE is required}"
: "${MYSQL_USER:?MYSQL_USER is required}"
: "${MYSQL_PASSWORD:?MYSQL_PASSWORD is required}"

MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_SSL_CA="${MYSQL_SSL_CA:-/migration/rds-global-bundle.pem}"
MYSQL_SSL_VERIFY_SERVER_CERT="${MYSQL_SSL_VERIFY_SERVER_CERT:-true}"
# Release identity is pinned by each image and can be overridden for local validation.
MIGRATION_RELEASE="${MIGRATION_RELEASE:-20260722}"
MIGRATION_ROOT="${MIGRATION_ROOT:-/migration}"
MIGRATION_MANIFEST="${MIGRATION_MANIFEST:-${MIGRATION_ROOT}/release-${MIGRATION_RELEASE}.manifest}"
EXPECTED_MIGRATION_COUNT="${EXPECTED_MIGRATION_COUNT:-5}"
PREDECESSOR_RELEASE="${PREDECESSOR_RELEASE:-20260717}"
PREDECESSOR_MANIFEST="${PREDECESSOR_MANIFEST:-${MIGRATION_ROOT}/release-${PREDECESSOR_RELEASE}.manifest}"
EXPECTED_PREDECESSOR_COUNT="${EXPECTED_PREDECESSOR_COUNT:-30}"

if [ "$MYSQL_DATABASE" != "ai_draw_io" ]; then
  echo "Refusing to migrate unexpected database: ${MYSQL_DATABASE}" >&2
  exit 1
fi

# MYSQL_PWD prevents the password from appearing in process arguments and task logs.
export MYSQL_PWD="$MYSQL_PASSWORD"

mysql_command() {
  case "$MYSQL_SSL_VERIFY_SERVER_CERT" in
    true)
      mariadb \
        --protocol=TCP \
        --host="$MYSQL_HOST" \
        --port="$MYSQL_PORT" \
        --user="$MYSQL_USER" \
        --ssl \
        --ssl-ca="$MYSQL_SSL_CA" \
        --ssl-verify-server-cert \
        --batch \
        --skip-column-names \
        "$@"
      ;;
    false)
      # Local disposable MySQL validation only; production must use endpoint verification.
      mariadb \
        --protocol=TCP \
        --host="$MYSQL_HOST" \
        --port="$MYSQL_PORT" \
        --user="$MYSQL_USER" \
        --ssl \
        --batch \
        --skip-column-names \
        "$@"
      ;;
    *)
      echo "MYSQL_SSL_VERIFY_SERVER_CERT must be true or false." >&2
      exit 1
      ;;
  esac
}

# Fail before any DDL when the negotiated connection is not encrypted.
tls_cipher="$(mysql_command --execute="SHOW SESSION STATUS LIKE 'Ssl_cipher';" | awk 'NR == 1 { print $2 }')"
if [ -z "$tls_cipher" ]; then
  echo "The database connection did not negotiate TLS." >&2
  exit 1
fi
echo "Verified encrypted database connection."

# Refuse to run this additive release unless every immutable predecessor checksum is present.
history_exists="$(mysql_command --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${MYSQL_DATABASE}' AND table_name='deployment_schema_history';")"
if [ "$history_exists" -ne 1 ]; then
  echo "Release ${PREDECESSOR_RELEASE} history is missing; run its release image first." >&2
  exit 1
fi

predecessor_count="$(mysql_command "$MYSQL_DATABASE" --execute="SELECT COUNT(*) FROM deployment_schema_history WHERE release_id='${PREDECESSOR_RELEASE}';")"
if [ "$predecessor_count" -ne "$EXPECTED_PREDECESSOR_COUNT" ]; then
  echo "Predecessor history count is ${predecessor_count}; expected ${EXPECTED_PREDECESSOR_COUNT}." >&2
  exit 1
fi

while IFS= read -r predecessor_file || [ -n "$predecessor_file" ]; do
  case "$predecessor_file" in
    ''|'#'*) continue ;;
  esac
  predecessor_path="${MIGRATION_ROOT}/sql/${predecessor_file}"
  expected_checksum="$(sha256sum "$predecessor_path" | awk '{print $1}')"
  recorded_checksum="$(mysql_command "$MYSQL_DATABASE" --execute="SELECT checksum_sha256 FROM deployment_schema_history WHERE script_name='${predecessor_file}' AND release_id='${PREDECESSOR_RELEASE}' LIMIT 1;")"
  if [ "$recorded_checksum" != "$expected_checksum" ]; then
    echo "Predecessor checksum is missing or changed: ${predecessor_file}" >&2
    exit 1
  fi
done < "$PREDECESSOR_MANIFEST"
echo "Verified predecessor release ${PREDECESSOR_RELEASE}."

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
