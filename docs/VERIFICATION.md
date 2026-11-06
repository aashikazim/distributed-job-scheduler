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
