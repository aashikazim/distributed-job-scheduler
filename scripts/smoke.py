#!/usr/bin/env python3
"""Test-only driver. Standard library only; reports attempts independently of receiver effects."""
import datetime
import json
import os
import subprocess
import threading
import time
import urllib.request
import uuid

API = os.environ["SMOKE_API_URL"].rstrip("/")
ADMIN = os.environ["SMOKE_ADMIN_URL"].rstrip("/")
TARGET = os.environ["SMOKE_TARGET_URL"].rstrip("/")
KEY = os.environ["SCHEDULER_API_KEY"]


def request(url, body=None, authenticated=False):
    headers = {"Content-Type": "application/json"}
    if authenticated:
        headers["X-API-Key"] = KEY
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=10) as response:
        return json.load(response)


def wait(check, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        threading.Event().wait(0.2)
    raise AssertionError("Smoke condition timed out")


def create(name, path):
    due = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=1)
    return request(API + "/api/v1/jobs", {
        "name": name, "jobType": "HTTP", "scheduleType": "ONE_TIME", "runAt": due.isoformat(),
        "timeoutSeconds": 90, "maxRetries": 0,
        "payload": {"url": TARGET + path, "method": "POST", "body": "{}", "expectedStatus": [200]},
    }, True)["id"]


def history(job):
    return request(API + "/api/v1/jobs/" + job + "/runs?size=100", authenticated=True)["content"]


def kill_owner(node):
    command = ["docker", "compose", "--env-file", os.environ["SMOKE_ENV_FILE"], "--profile", "smoke"]
    containers = subprocess.check_output(command + ["ps", "-q", "app-worker"], text=True).split()
    for container in containers:
        hostname = subprocess.check_output(["docker", "inspect", "--format", "{{.Config.Hostname}}", container], text=True).strip()
        if node.startswith(hostname + "-"):
            subprocess.run(["docker", "kill", container], check=True, stdout=subprocess.DEVNULL)
            print("Killed active worker", container[:12], flush=True)
            return
    raise AssertionError("Could not identify the owning worker in this smoke project")


def main():
    prefix = "smoke-" + uuid.uuid4().hex[:10]
    jobs = [create(prefix + "-0", "/hold")]
    wait(lambda: jobs[0] in request(ADMIN + "/stats")["jobs"])
    runs = history(jobs[0])
    assert len(runs) == 1 and runs[0]["status"] == "RUNNING", runs
    kill_owner(runs[0]["nodeId"])
    jobs.extend(create(prefix + "-" + str(i), "/ok") for i in range(1, 100))
    wait(lambda: all(request(API + "/api/v1/jobs/" + job, authenticated=True)["status"] == "COMPLETED" for job in jobs))
    # Unblock the abandoned receiver handler, after the replacement has already acknowledged its retry.
    with urllib.request.urlopen(ADMIN + "/release", timeout=5) as response:
        response.read()
    stats = request(ADMIN + "/stats")["jobs"]
    runs = [run for job in jobs for run in history(job)]
    for job in jobs:
        job_runs = [run for run in runs if run["jobId"] == job]
        assert sum(run["status"] == "SUCCEEDED" for run in job_runs) == 1, job_runs
        assert all(run["status"] in ("SUCCEEDED", "LOST") for run in job_runs), job_runs
        assert stats[job]["effects"] == 1, stats[job]
    lost = sum(run["status"] == "LOST" for run in runs)
    attempts = sum(stats[job]["attempts"] for job in jobs)
    assert lost >= 1 and stats[jobs[0]]["attempts"] >= 2, (lost, attempts)
    assert all(stats[job]["attempts"] == 1 for job in jobs[1:]), stats
    print(f"PASS: {len(jobs)} completed jobs; 100 SUCCEEDED runs; {lost} LOST run(s).")
    print(f"HTTP attempts={attempts}; logical receiver effects=100 (one per job).")
    print("99 unaffected jobs had exactly one HTTP attempt. Recovery repeated the interrupted request;")
    print("the idempotent receiver deduplicated its effect. Scheduler delivery remains at-least-once.")


if __name__ == "__main__":
    main()
