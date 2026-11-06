# Verification record

## Phase 6 — Docker

Commands (the smoke script creates its own isolated project):

```bash
./mvnw verify
./scripts/smoke.sh
```

Observed: 111 tests passed, zero skipped. Image build passed; container UID 10001,
read-only root filesystem, readiness healthy. Compose scaled to three workers.
Killed an active worker after the receiver accepted its request. All 100 jobs completed:
100 SUCCEEDED runs, 1 LOST run, 101 HTTP attempts, 100 receiver effects. The remaining
99 jobs each had exactly one HTTP attempt. No jobs were lost; the receiver deduplicated
the repeated effect. This is at-least-once delivery, not a universal exactly-once claim.
The isolated stack and volume were removed; preexisting local services were preserved.

## Phase 7 — Kubernetes

Verified tools: kind 0.33.0, kubectl 1.37.2, Kubernetes 1.37.0 on arm64.
The isolated cluster uses /tmp/scheduler-kind.kubeconfig. Commands executed:

```bash
kind create cluster --name scheduler --kubeconfig /tmp/scheduler-kind.kubeconfig --wait 120s
export IMAGE_TAG="$(git rev-parse --short HEAD)"
docker build --target app --build-arg GIT_SHA="$IMAGE_TAG" -t "job-scheduler:$IMAGE_TAG" .
docker build --target smoke-target -t "job-scheduler-smoke:$IMAGE_TAG" .
KUBECONFIG=/tmp/scheduler-kind.kubeconfig KIND_CLUSTER_NAME=scheduler KEEP_CLUSTER=1 SKIP_IMAGE_BUILD=1 ./scripts/k8s-smoke.sh
./mvnw verify
kubectl apply --dry-run=server -k k8s/base
kubectl apply --dry-run=server -k k8s/overlays/prod
```

The script loaded images, created a real Secret separately, rendered/applied k8s/smoke,
and waited for PostgreSQL/API/worker/receiver rollout. It executed:

```bash
kubectl -n scheduler scale deploy/scheduler-worker --replicas=1
kubectl -n scheduler scale deploy/scheduler-worker --replicas=5
kubectl -n scheduler scale deploy/scheduler-worker --replicas=1
kubectl -n scheduler delete pod scheduler-worker-c6c458c46-jhbb5 --wait=false
```

Observed 1, 5, then 1 ready workers. The deleted pod owned a RUNNING job and completed
it during graceful termination; its replacement completed queued work. Results:
100 COMPLETED jobs, 100 SUCCEEDED runs, 0 LOST runs, 100 HTTP attempts, 100 effects,
six node identities. Final app/database/receiver pods Ready; 1Gi PVC Bound; worker UID 10001.
111 Maven tests passed, none skipped. Base/prod API-server dry-runs passed without modifying
the running smoke overlay. CPU HPA is retained in prod but intentionally absent in the local
manual exercise; metrics-server/CPU autoscaling was not exercised.

## Phase 8 — CI/CD and final docs

Local commands passed:

```bash
./mvnw verify
bash -n docker/entrypoint.sh scripts/*.sh
python3 -m py_compile scripts/*.py
./scripts/render-k8s.sh prod "$(git rev-parse HEAD)"
```

111 tests passed, zero failures/errors/skips. All README/cloud Bash blocks passed syntax
checks and relative documentation links resolved. The fresh .env helper was exercised in a
temporary copy: random credentials created with mode 600, existing configuration preserved.
Docker builds Java on BUILDPLATFORM so multi-platform publishing avoids emulated compilation.

Remote CI was executed by pushing implementation commit 5b88065 to
codex/finish-phases-6-8. [Run 42177732423](https://github.com/aashikazim/distributed-job-scheduler/actions/runs/42177732423)
completed successfully: verify passed (Java/Testcontainers, syntax, Kustomize, reports),
docker-smoke passed (native amd64 image plus active-worker crash scenario: UID 10001,
read-only/healthy; 100 completed jobs, 100 successes, 1 LOST, 101 HTTP attempts, 100 effects), publish skipped
because this was a verification branch. The earlier local Docker/kind checks ran on arm64.
The workflow is configured to publish amd64/arm64 SHA-tagged GHCR images after successful main
pushes; that publication/pull path has not been executed during this task.

All six AGENTS.md section 17 items are audited in [DEFINITION_OF_DONE.md](DEFINITION_OF_DONE.md).
Cloud provider deployments/TLS and CPU HPA load response remain environment-specific and
unverified. The kind cluster scheduler remains healthy for review, with kubeconfig at
/tmp/scheduler-kind.kubeconfig; no preexisting Docker services or user kubeconfig were modified.
