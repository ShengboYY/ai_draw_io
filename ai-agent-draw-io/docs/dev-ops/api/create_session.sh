#!/usr/bin/env bash
set -euo pipefail

# Anonymous ownership is authenticated by the server-issued HttpOnly cookie, never by owner id.
COOKIE_JAR="${COOKIE_JAR:-/tmp/ai-drawio-api-cookies.txt}"

curl --fail --silent --show-error \
  --cookie-jar "$COOKIE_JAR" \
  --request POST \
  'http://127.0.0.1:8091/api/v1/anonymous-workspaces'

# Fetch a CSRF token, then copy data.token into X-XSRF-TOKEN for the state-changing request.
curl --fail --silent --show-error \
  --cookie "$COOKIE_JAR" \
  --cookie-jar "$COOKIE_JAR" \
  'http://127.0.0.1:8091/api/v1/auth/csrf'

curl --fail --silent --show-error \
  --cookie "$COOKIE_JAR" \
  --header 'Content-Type: application/json' \
  --header 'X-XSRF-TOKEN: replace-with-data.token-from-previous-response' \
  --data '{"agentId":"100003"}' \
  'http://127.0.0.1:8091/api/v1/create_session'
