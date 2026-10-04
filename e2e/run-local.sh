#!/usr/bin/env bash
# Runs the Playwright UI tests locally: disposable Postgres in Docker, fresh API jar and
# frontend build, then the suite. Extra arguments go to `playwright test`.
set -euo pipefail
cd "$(dirname "$0")"

PG_CONTAINER=jb-e2e-postgres
if ! docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
  docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  docker run -d --name "$PG_CONTAINER" -p 5433:5432 \
    -e POSTGRES_DB=jb_e2e -e POSTGRES_USER=jb -e POSTGRES_PASSWORD=jb postgres:16-alpine >/dev/null
  until docker exec "$PG_CONTAINER" pg_isready -U jb -d jb_e2e >/dev/null 2>&1; do sleep 1; done
fi

(cd ../backend && mvn -B -q -DskipTests package)
(cd ../frontend && npm ci --no-audit --no-fund && npm run build)
npm ci --no-audit --no-fund
npx playwright test "$@"
