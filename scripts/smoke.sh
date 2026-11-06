#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for tool in docker python3 git; do
  command -v "$tool" >/dev/null || { echo "Required tool missing: $tool" >&2; exit 1; }
done
export IMAGE_TAG="${IMAGE_TAG:-$(git rev-parse --short HEAD)}"
export COMPOSE_PROJECT_NAME="${SMOKE_PROJECT_NAME:-scheduler-smoke-$$}"
export API_PORT="${SMOKE_API_PORT:-18090}"
export DB_PORT="${SMOKE_DB_PORT:-15437}"
export SMOKE_PORT="${SMOKE_TARGET_PORT:-19091}"
export SCHEDULER_HTTP_ALLOWED_HOSTS=smoke-target
export SCHEDULER_POLL_INTERVAL=200ms
export SCHEDULER_LEASE_DURATION=6s
export SCHEDULER_HEARTBEAT_INTERVAL=1s
export SCHEDULER_JANITOR_INTERVAL=1s
export SCHEDULER_MAX_CONCURRENT_RUNS=5
export SCHEDULER_SHUTDOWN_GRACE_PERIOD=5s
umask 077
smoke_env=$(mktemp)
python3 - "$smoke_env" <<'PY'
import secrets,sys
with open(sys.argv[1],'w') as output:
    output.write('DB_USER=scheduler\nDB_PASSWORD='+secrets.token_hex(24)+'\nSCHEDULER_API_KEY='+secrets.token_hex(32)+'\n')
PY
set -a
source "$smoke_env"
set +a
compose=(docker compose --env-file "$smoke_env" --profile smoke)
if [[ -n "$("${compose[@]}" ps -aq)" ]]; then
  rm "$smoke_env"
  echo "Refusing to reuse an existing smoke project; choose a new SMOKE_PROJECT_NAME." >&2
  exit 1
fi
cleanup() {
  if [[ "${KEEP_STACK:-0}" != 1 ]]; then
    "${compose[@]}" down --volumes --remove-orphans
    rm -f "$smoke_env"
  else
    echo "Stack retained as $COMPOSE_PROJECT_NAME; credentials retained at $smoke_env (mode 600)."
  fi
}
trap cleanup EXIT
"${compose[@]}" up -d --build --wait --wait-timeout 240 --scale app-worker=3
api_container=$("${compose[@]}" ps -q app-api)
[[ "$(docker exec "$api_container" id -u)" == 10001 ]]
[[ "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}} {{.State.Health.Status}}' "$api_container")" == 'true healthy' ]]
echo "Image verified: UID 10001, read-only root filesystem, readiness healthy."
export SMOKE_ENV_FILE="$smoke_env"
export SMOKE_API_URL="http://127.0.0.1:$API_PORT"
export SMOKE_ADMIN_URL="http://127.0.0.1:$SMOKE_PORT"
export SMOKE_TARGET_URL=http://smoke-target:8080
python3 scripts/smoke.py
