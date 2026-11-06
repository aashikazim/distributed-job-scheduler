#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for tool in docker kind kubectl python3 git; do
  command -v "$tool" >/dev/null || { echo "Required tool missing: $tool" >&2; exit 1; }
done
export IMAGE_TAG="${IMAGE_TAG:-$(git rev-parse --short HEAD)}"
cluster="${KIND_CLUSTER_NAME:-scheduler-smoke-$$}"
export KUBECONFIG="${KUBECONFIG:-$(mktemp)}"
created=0
if ! kind get clusters | grep -Fx "$cluster" >/dev/null; then
  kind create cluster --name "$cluster" --kubeconfig "$KUBECONFIG" --wait 120s
  created=1
else
  kind export kubeconfig --name "$cluster" --kubeconfig "$KUBECONFIG"
fi
if kubectl get namespace scheduler >/dev/null 2>&1; then
  echo "Refusing to replace existing scheduler namespace. Choose a fresh test cluster." >&2
  exit 1
fi
umask 077
smoke_env=$(mktemp)
python3 - "$smoke_env" <<'PY'
import secrets,sys
with open(sys.argv[1],'w') as output:
    output.write('DB_URL=jdbc:postgresql://scheduler-postgres:5432/scheduler\nDB_USER=scheduler\nDB_PASSWORD='
                 +secrets.token_hex(24)+'\nSCHEDULER_API_KEY='+secrets.token_hex(32)+'\n')
PY
set -a
source "$smoke_env"
set +a
api_forward=''
target_forward=''
cleanup() {
  [[ -z "$api_forward" ]] || kill "$api_forward" 2>/dev/null || true
  [[ -z "$target_forward" ]] || kill "$target_forward" 2>/dev/null || true
  if [[ "${KEEP_CLUSTER:-0}" == 1 ]]; then
    echo "Cluster retained: $cluster; kubeconfig: $KUBECONFIG; credentials file: $smoke_env (mode 600)."
  else
    if [[ "$created" == 1 ]]; then
      kind delete cluster --name "$cluster"
    else
      kubectl delete namespace scheduler --wait=false
    fi
    rm -f "$smoke_env"
  fi
}
trap cleanup EXIT
if [[ "${SKIP_IMAGE_BUILD:-0}" != 1 ]]; then
  docker build --target app --build-arg GIT_SHA="$IMAGE_TAG" -t "job-scheduler:$IMAGE_TAG" .
  docker build --target smoke-target -t "job-scheduler-smoke:$IMAGE_TAG" .
fi
kind load docker-image --name "$cluster" "job-scheduler:$IMAGE_TAG" "job-scheduler-smoke:$IMAGE_TAG"
kubectl create namespace scheduler
kubectl -n scheduler create secret generic scheduler-secrets --from-env-file="$smoke_env"
./scripts/render-k8s.sh smoke "$IMAGE_TAG" | kubectl apply -f -
kubectl -n scheduler rollout status statefulset/scheduler-postgres --timeout=180s
for deployment in scheduler-api scheduler-worker scheduler-smoke-target; do
  kubectl -n scheduler rollout status "deployment/$deployment" --timeout=300s
done
export SMOKE_API_URL="http://127.0.0.1:${K8S_SMOKE_API_PORT:-18091}"
export SMOKE_ADMIN_URL="http://127.0.0.1:${K8S_SMOKE_TARGET_PORT:-19092}"
export SMOKE_TARGET_URL=http://scheduler-smoke-target:8080
kubectl -n scheduler port-forward svc/scheduler-api "${K8S_SMOKE_API_PORT:-18091}:80" > /tmp/scheduler-api-forward-$$.log 2>&1 &
api_forward=$!
kubectl -n scheduler port-forward svc/scheduler-smoke-target "${K8S_SMOKE_TARGET_PORT:-19092}:8080" > /tmp/scheduler-target-forward-$$.log 2>&1 &
target_forward=$!
python3 scripts/k8s-smoke.py
