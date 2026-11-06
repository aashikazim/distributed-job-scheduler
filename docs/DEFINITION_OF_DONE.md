# Definition of Done audit

This audit follows AGENTS.md section 17. Evidence is in [VERIFICATION.md](VERIFICATION.md).

| Item | Evidence/status |
| --- | --- |
| All phases; Maven verify; CI green | Phases 0–8 implementation complete; local 111 tests pass; remote CI pending |
| Exactly-once-per-slot under 2–4 workers | Verified for 500 one-time slots across two independent loops; all execute once during normal operation |
| Crash recovery, automated plus container check | Verified natural-expiry takeover and Docker active-worker kill; old run LOST, replacement SUCCEEDED |
| Non-root image, probes | Verified Docker UID 10001/read-only root/healthy readiness and Kubernetes UID 10001/probes |
| Kubernetes scale/delete without loss/duplicates | Verified 1 → 5 → 1 and graceful active-pod deletion: 100 jobs/runs/HTTP attempts, zero LOST/duplicates |
| README/learning explanations | Complete architecture, quickstart, APIs, claim/lease/heartbeat, delivery, cloud/scaling guides |

A hard crash is not a global exactly-once guarantee: Docker observed 101 attempts for 100 jobs,
with 100 deduplicated receiver effects. The normal automated and graceful K8s checks observed
exactly-once execution; those observations do not change at-least-once delivery semantics.

Not yet verified: remote CI, main-only GHCR publish/pull, cloud deployment/TLS in an actual
EKS/GKE/AKS account, or CPU HPA under metrics-server/load. DNS/socket pinning is not implemented;
network egress controls are needed for rebinding-resistant enforcement. KEDA and other stretch
items were intentionally not added.
