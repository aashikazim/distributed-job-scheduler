# 7. Scaling workers and recovering work — Q&A

## What is horizontal scaling versus vertical scaling?

Horizontal scaling changes the number of processes/pods: one worker becomes five workers.
Vertical scaling changes resources available to a process: more CPU or memory for each worker.
Increasing `scheduler.max-concurrent-runs` changes application concurrency; it is a separate
setting from both replica count and container resources.

## What happens when we scale from one worker to five?

1. The Deployment's desired worker count changes to five.
2. Kubernetes creates four additional pods when cluster capacity allows.
3. Each process starts, migrates/checks the schema, connects to PostgreSQL, and starts its loop.
4. Each loop reserves free execution capacity before claiming due rows.
5. PostgreSQL's atomic claim distributes available jobs among competing workers.

Existing work is not transferred from the original worker to the new ones. New workers take
eligible unowned or expired work. There is no leader election, job partition rebalancing, or
shared Java queue between these pods.

## Does five workers mean five times the speed?

It increases potential capacity, but actual throughput can be limited by the target service,
network, PostgreSQL, available cluster resources, or the number of due jobs. A slow webhook
may occupy an execution slot while using little CPU.

At the default 50 concurrent runs per worker, five workers allow up to 250 in-flight runs
across those workers. This is a ceiling, not a promise that 250 jobs start immediately or
finish each second. Claims are also bounded by batch size (default 20) and free slots per poll.

As a rough intuition, 50 constantly occupied slots with 2-second requests could complete about
25 requests/second before overhead and other bottlenecks. Changing request duration changes
that estimate; benchmark the actual workload.

## Why not claim everything and keep it in a local queue?

A large claimed backlog would be unavailable to other workers while waiting locally. This
project claims only what it has free capacity to start. Additional pods can therefore find
unclaimed work immediately, and a crash leaves fewer reserved jobs waiting for lease expiry.

## What happens when five workers scale down to one?

Kubernetes chooses pods to terminate; it does not know which has the shortest remaining jobs.
On normal termination, Java receives SIGTERM and Spring invokes the worker lifecycle shutdown:

```text
stop polling -> wait for in-flight work within configured grace
             -> cancel/release remaining owned work if necessary
             -> process exits
```

The application shutdown grace is 60 seconds by default; the pod termination grace is 90 seconds.
Jobs that finish within the application grace can record success normally. Remaining leases
are released when possible; if the process cannot release them, their expiry permits recovery.
The surviving worker continues claiming work. If all workers are removed, jobs remain stored
and wait for workers to return, subject to their misfire policy.

## Is graceful termination the same as `docker kill` or a machine crash?

No. A hard kill usually gives the process no cleanup opportunity. The worker stops heartbeating,
and unfinished ownership expires. Recovery may therefore wait up to the remaining lease time
plus polling/startup time. Graceful shutdown can finish or release work sooner.

If an HTTP target applied an action before the worker died, the recovering worker may repeat
the request. Cancellation also cannot undo a remote effect. Lease ownership prevents stale
database writes; it cannot make an external system roll back an action.

## How do targets deduplicate repeated work?

Every request carries `X-Job-Id`, `X-Job-Run-Id`, and `X-Job-Attempt`. Run IDs identify individual
attempts; a recovery attempt gets another run ID. To prevent a duplicate business action across
attempts, a target needs a stable business-operation key and a durable deduplication design.

The smoke receiver uses job ID because its jobs are one-time operations. For recurring jobs,
job ID alone is too broad: tomorrow's legitimate action must not be mistaken for today's retry.
The project does not provide a universal stable schedule-slot idempotency key. Design that
contract with your target, often by including a business-operation ID in the payload.

## What is HPA, and how does it decide to scale?

The [HorizontalPodAutoscaler](../../k8s/base/hpa.yaml) changes worker replica count between 2
and 10 based on average CPU utilization, targeting 60% of CPU requests. The baseline requires
a working resource metrics pipeline, commonly metrics-server, plus CPU requests on pods.
A 100m CPU request means a 60% target is approximately 60m average usage per worker.

A simplified estimate is `desired replicas ≈ current replicas × current utilization / target`.
Kubernetes also applies readiness handling, missing-metric rules, stabilization, and scaling
policies, so it is not an immediate exact arithmetic trigger.

CPU HPA does not automatically read `scheduler_jobs_due` or `scheduler_lag_seconds`. Queue/lag
scaling through KEDA is a possible extension, not implemented here. HTTP-heavy work can have
low CPU despite a large backlog, so CPU alone may be a poor demand signal.

## Why can I manually scale the local overlay to one?

The local overlay removes HPA deliberately. The base/prod HPA has minimum 2 and can overwrite
manual replica changes while it is active. Use the local overlay for the documented 1 → 5 → 1
exercise. Do not infer that a production HPA has been tested under load from the local exercise.

## Do more workers also mean more database connections?

Yes. Every pod has its own Hikari pool. The Kubernetes configuration caps each at five
connections; the application's standalone default is ten. For two API pods and ten workers
with five connections each, the combined pool ceiling is `(2 + 10) × 5 = 60`, before migrations,
admin tools, and rollout surge pods. Budget below the database connection limit with headroom.

Do not set pool size equal to execution concurrency automatically. Jobs mostly wait on HTTP;
they borrow DB connections for short operations. Measure database contention and latency.

## Can API pods scale independently?

Yes. More API pods handle more client requests; more worker pods process more scheduled work.
Both use the same database, so it can become a shared bottleneck. Scaling API replicas does
not create more worker loops when those pods run with role API.

## Which metrics help explain scaling behavior?

| Signal | What to ask |
|---|---|
| `scheduler_jobs_due` | Is eligible-looking due work accumulating? |
| `scheduler_lag_seconds` | How old is the oldest active due timestamp? |
| `scheduler_running` | How many local execution slots are occupied? |
| Claim/run duration | Is time spent in database coordination or execution? |
| Run outcomes | Are retries, timeouts, or dead letters creating load? |

Due/lag snapshots count ACTIVE due rows, which can include currently leased work until its
schedule advances. They are not a precise count of immediately claimable rows. Each pod's
due/lag view refers to the shared database: use a suitable maximum across fresh snapshots,
rather than summing duplicated global counts. Local running gauges can be summed across pods.

## Try the local scaling exercise

Use an already deployed local overlay, after checking the context. These commands change
replica count in that cluster:

```bash
kubectl config current-context
kubectl -n scheduler scale deployment/scheduler-worker --replicas=5
kubectl -n scheduler rollout status deployment/scheduler-worker --timeout=180s
kubectl -n scheduler get pods -l app=scheduler-worker
kubectl -n scheduler scale deployment/scheduler-worker --replicas=1
kubectl -n scheduler rollout status deployment/scheduler-worker --timeout=180s
```

Expected observation: extra worker pods appear, become ready, then terminate on downscale.
Watching pod count alone does not verify job correctness. The isolated `./scripts/k8s-smoke.sh`
exercise additionally creates jobs and verifies results. Recorded results are in
[VERIFICATION](../VERIFICATION.md): local graceful scaling/deletion produced 100 successful
jobs and 100 HTTP attempts; Docker hard-kill recovery produced 101 attempts and 100 effects.

**Check your understanding:** Does Kubernetes guarantee each webhook happens exactly once when
scaling? No. Kubernetes manages processes; database coordination and target idempotency determine
how work behaves across failures.
