#!/bin/sh
set -eu

# Keep API origin runtime-configurable without rebuilding the Next.js image.
node -e 'const fs = require("fs"); const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL || "/api/v1"; fs.writeFileSync("/tmp/env-config.js", `window.__ENV = ${JSON.stringify({ NEXT_PUBLIC_API_BASE_URL: apiBaseUrl }, null, 2)};\n`);'

exec "$@"
