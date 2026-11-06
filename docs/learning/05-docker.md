# 5. Docker and Compose — Q&A

## Why put a Java application in a container?

A container packages the application with a chosen runtime and starts it with explicit settings.
That reduces differences between developer and deployment machines. Our runtime image includes
Java 21, so a host with an older Java version can still run the application through Docker.
The host still needs a compatible container runtime and enough resources.

## What is the difference between an image and a container?

An image is a reusable template made of filesystem layers and startup metadata. A container is
a running or stopped instance of that image, with its own process environment and writable layer.
Five worker containers can use the same image and still be five independent JVM processes.

A container is not a full virtual machine. Linux containers use kernel isolation and resource
controls; on systems such as macOS, Docker typically supplies a Linux VM underneath them.
Containers are not all-purpose security boundaries just because they package an application.

## What does our Dockerfile do?

Read the [Dockerfile](../../Dockerfile):

1. The build stage uses Maven and JDK 21 to compile/package the application.
2. It copies dependency/build files before source so unchanged dependencies can use cached layers.
3. The final application stage copies the built JAR into a Temurin 21 JRE image.
4. An entrypoint starts Java as the container's main process, allowing shutdown signals to reach it.

Build tools and source are not needed in the application runtime image. A separate optional
`smoke-target` stage packages the test receiver; it is not part of the normal scheduler runtime.
The Docker build skips executing tests because verification runs separately; an image build
alone is not proof that the tests passed.

## Why tag an image with a Git SHA?

The tag links a deployment to its source revision. `dev` can mean different code tomorrow.
A SHA tag is more informative, although tags can technically be overwritten. For stronger
artifact identity, image digests identify the exact image content.

## How do containers find PostgreSQL?

[Compose](../../docker-compose.yml) creates a network with service DNS names. The application
connects to `postgres:5432` inside that network. `localhost` inside the API container means
the API container itself, not your host and not the PostgreSQL container.

A published mapping such as host port 15432 to container port 5432 allows host programs to
connect. It does not change the port other containers use. The same distinction applies to
host API port 8080 and the application's container port 8080.

## Where does database data live?

PostgreSQL data lives in a named Docker volume. Replacing a PostgreSQL container can reuse
that volume. `docker compose down` normally preserves it; `down --volumes` removes it and
therefore discards this stack's local database. The worker containers store durable job state
in PostgreSQL and do not need their own data volumes.

## How can API and workers use one image?

They run the same JAR with different environment settings:

```text
same image -> app-api     SCHEDULER_ROLE=API
           -> worker 1    SCHEDULER_ROLE=WORKER
           -> worker 2    SCHEDULER_ROLE=WORKER
```

Compose supplies credentials and role settings at startup. Credentials are not baked into the
image. An image rebuild changes application code; recreating a container is needed to apply
changed startup environment values. A simple process restart does not redefine its configuration.

## What do non-root and read-only mean here?

The application runs as UID 10001, limiting its operating-system privileges. Compose makes its
root filesystem read-only and provides writable temporary space separately. PostgreSQL needs a
writable data volume. Read-only filesystems do not prevent network access or replace API auth.

Java's `MaxRAMPercentage=75` sets a heap sizing input relative to detected memory. Heap is only
part of process memory; threads, direct buffers, class metadata, and other native memory also
need space. Container memory limits still matter.

## Does Compose decide which worker executes a job?

No. Compose starts the requested number of containers. Each worker independently polls
PostgreSQL. The database claim and lease algorithm decides job ownership.
The Docker healthcheck reports health; standalone Compose does not become a Kubernetes-style
controller that replaces unhealthy containers merely because they report unhealthy.

## Try inspecting and scaling a local stack

First follow the [quickstart](../../README.md), including creating/loading `.env`. Then:

```bash
docker compose ps
docker compose up -d --scale app-worker=3
docker compose logs --tail=30 app-worker
```

Expected observation: three worker containers using the same image, with different node IDs.
Scaling changes the number of JVMs; it does not alter a single JVM's concurrency setting.
Only run these commands against your intended local Compose project.

**Check your understanding:** If you rebuild an image, have already running containers changed?
No. Recreate them using the new image, for example with the documented Compose `up --build` flow.
