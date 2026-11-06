#!/usr/bin/env python3
"""Graceful Kubernetes scale/delete exercise; shared API helpers use only the standard library."""
import collections
import json
import subprocess
import urllib.error
import uuid

import smoke


def kubectl(*args, capture=False):
    command = ["kubectl", "-n", "scheduler", *args]
    if capture:
        return json.loads(subprocess.check_output(command, text=True))
    print("+", " ".join(command), flush=True)
    subprocess.run(command, check=True)


def scale(replicas):
    kubectl("scale", "deploy/scheduler-worker", "--replicas=" + str(replicas))
    kubectl("rollout", "status", "deploy/scheduler-worker", "--timeout=300s")
    smoke.wait(lambda: kubectl("get", "deployment", "scheduler-worker", "-o", "json", capture=True)["status"].get("readyReplicas", 0) == replicas)
    print("Ready worker replicas:", replicas, flush=True)


def ready():
    try:
        smoke.request(smoke.API + "/actuator/health/readiness")
        smoke.request(smoke.ADMIN + "/stats")
        return True
    except (OSError, urllib.error.URLError):
        return False


def finished(jobs):
    return all(smoke.request(smoke.API + "/api/v1/jobs/" + job, authenticated=True)["status"] == "COMPLETED" for job in jobs)


def main():
    smoke.wait(ready, 30)
    prefix = "k8s-smoke-" + uuid.uuid4().hex[:10]
    scale(1)
    jobs = [smoke.create(prefix + "-" + str(i), "/slow") for i in range(33)]
    smoke.wait(lambda: finished(jobs))
    scale(5)
    second = [smoke.create(prefix + "-" + str(i), "/slow") for i in range(33, 66)]
    jobs.extend(second)
    smoke.wait(lambda: finished(second))
    scale(1)
    held = smoke.create(prefix + "-66", "/hold")
    jobs.append(held)
    smoke.wait(lambda: held in smoke.request(smoke.ADMIN + "/stats")["jobs"])
    jobs.extend(smoke.create(prefix + "-" + str(i), "/slow") for i in range(67, 100))
    active = [run for run in smoke.history(held) if run["status"] == "RUNNING"]
    assert len(active) == 1, active
    node = active[0]["nodeId"]
    pods = kubectl("get", "pods", "-l", "app=scheduler-worker", "-o", "json", capture=True)["items"]
    owner = next(pod["metadata"]["name"] for pod in pods if node.startswith(pod["metadata"]["name"] + "-"))
    kubectl("delete", "pod", owner, "--wait=false")
    with smoke.urllib.request.urlopen(smoke.ADMIN + "/release", timeout=5) as response:
        response.read()
    smoke.wait(lambda: finished(jobs), 180)
    kubectl("rollout", "status", "deploy/scheduler-worker", "--timeout=300s")
    runs = [run for job in jobs for run in smoke.history(job)]
    stats = smoke.request(smoke.ADMIN + "/stats")["jobs"]
    assert len(jobs) == len(runs) == 100, (len(jobs), len(runs))
    assert all(run["status"] == "SUCCEEDED" for run in runs), collections.Counter(run["status"] for run in runs)
    assert all(stats[job]["attempts"] == stats[job]["effects"] == 1 for job in jobs), stats
    assert len({run["nodeId"] for run in runs}) >= 2
    print("PASS: scaled workers 1 -> 5 -> 1; deleted owning pod during an active run.")
    print("100 COMPLETED jobs; 100 SUCCEEDED runs; 0 LOST runs; 100 HTTP attempts; 100 receiver effects.")
    print("No missing jobs or duplicate attempts in this graceful deletion/scale exercise.")
    print("Node execution counts:", dict(collections.Counter(run["nodeId"] for run in runs)))


if __name__ == "__main__":
    main()
