# Managed PostgreSQL deployment: EKS, GKE, AKS

These are deployment instructions, not claims that cloud resources were provisioned or tested.
The verified environment is local kind. Use an existing Kubernetes cluster and PostgreSQL 16
instance with a `scheduler` database/user; give that user schema creation rights for Flyway.
Keep the database private and reachable from the cluster on TCP 5432. No Redis, broker,
cloud-specific scheduler, or additional application dependency is required.

## Common deployment steps

Select the provider context below, obtain the provider's current trusted CA bundle, and set
DB_URL with the server certificate's actual DNS hostname. Use verified TLS, with the CA mounted
at `/etc/postgresql-ca/ca.crt`; do not disable certificate verification to work around DNS mistakes.
Supply DB_USER, DB_PASSWORD, and SCHEDULER_API_KEY from your secure environment/secret system.
The API key should be random and independent of the database password.

```bash
# Set these after selecting the provider/context below.
export DB_USER=scheduler
read -r -s -p 'Database password: ' DB_PASSWORD; printf '\n'; export DB_PASSWORD
read -r -s -p 'Scheduler API key: ' SCHEDULER_API_KEY; printf '\n'; export SCHEDULER_API_KEY
export CA_FILE="${CA_FILE:?Set CA_FILE from the provider step}"
: "${DB_URL:?Set DB_URL from the provider step}"
export IMAGE_TAG=REPLACE_WITH_PUBLISHED_40_CHARACTER_GIT_SHA
kubectl create namespace scheduler --dry-run=client -o yaml | kubectl apply -f -

# Serialize into a private temporary file, not command arguments or committed YAML.
umask 077
secret_file=$(mktemp)
python3 - "$secret_file" <<'PY'
import os,sys
with open(sys.argv[1],'w') as output:
    for key in ('DB_URL','DB_USER','DB_PASSWORD','SCHEDULER_API_KEY'):
        value=os.environ[key]
        if not value or '\n' in value:
            raise SystemExit('Missing or multiline secret: '+key)
        output.write(key+'='+value+'\n')
PY
kubectl -n scheduler create secret generic scheduler-secrets \
  --from-env-file="$secret_file" --dry-run=client -o yaml | kubectl apply -f -
rm -f "$secret_file"
kubectl -n scheduler create configmap scheduler-database-ca \
  --from-file="ca.crt=$CA_FILE" --dry-run=client -o yaml | kubectl apply -f -
./scripts/render-k8s.sh prod "$IMAGE_TAG" | kubectl apply -f -
kubectl -n scheduler rollout status deploy/scheduler-api --timeout=300s
kubectl -n scheduler rollout status deploy/scheduler-worker --timeout=300s
kubectl -n scheduler get pods,pdb,hpa
kubectl -n scheduler port-forward svc/scheduler-api 8080:80
```

The prod overlay does not create PostgreSQL. Its Deployments read the managed DB connection
from Secret, and its CA volume is supplied separately. Secret templates are deliberately
excluded from Kustomize so they cannot replace real credentials. Changing a Secret or ConfigMap
does not update existing process environments: use `kubectl rollout restart deployment/scheduler-api deployment/scheduler-worker -n scheduler` after coordinating credential/CA rotation with the DB.

The published image is `ghcr.io/aashikazim/distributed-job-scheduler:$IMAGE_TAG`. For private
GHCR packages, configure imagePullSecrets on the deployments or a dedicated ServiceAccount;
the repository's default ServiceAccount does not carry registry credentials. Alternatively
copy the image into your approved ECR/Artifact Registry/ACR and change the prod image name.
Keep service-account token automount disabled unless an explicitly added integration needs it.

## EKS + RDS PostgreSQL 16

Use a cluster and RDS instance with private routing between their subnets. Permit database
port 5432 only from the node/pod security groups that need it. Use the RDS endpoint DNS name,
not an arbitrary alias, for hostname verification. [RDS TLS documentation](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.SSL.html).

```bash
aws eks update-kubeconfig --region "$AWS_REGION" --name "$EKS_CLUSTER"
export RDS_ENDPOINT=YOUR_INSTANCE_ENDPOINT.rds.amazonaws.com
curl -fsSL https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem -o /tmp/rds-ca.pem
export CA_FILE=/tmp/rds-ca.pem
export DB_URL="jdbc:postgresql://${RDS_ENDPOINT}:5432/scheduler?sslmode=verify-full&sslrootcert=/etc/postgresql-ca/ca.crt"
```

Then run the common steps. RDS supports database-enforced TLS; keep its TLS policy enabled.
The provider CA bundle and verified hostname prevent accepting an unrelated server certificate.
The app uses ordinary PostgreSQL credentials; IAM database-token refresh is not implemented.

## GKE + Cloud SQL PostgreSQL 16

Use VPC-native GKE and private Cloud SQL connectivity in the same reachable network.
This stack connects directly, without adding the Cloud SQL Auth Proxy or connector dependency.
[Google's GKE connection guide](https://docs.cloud.google.com/sql/docs/postgres/connect-kubernetes-engine)
describes direct private connectivity as well as its recommended proxy alternative.

For this guide's `verify-full` URL, use a shared-CA or customer-managed-CA configuration that
supports DNS server identity verification. With a shared CA, inspect `dnsNames` in the instance
description and create the corresponding private DNS record pointing at its private endpoint.
Obtain the current CA bundle from the instance/provider, and require encrypted connections.
An arbitrary private IP/per-instance-CA hostname combination may not support `verify-full`.
[Cloud SQL TLS and hostname verification](https://docs.cloud.google.com/sql/docs/postgres/configure-ssl-instance).

```bash
gcloud container clusters get-credentials "$GKE_CLUSTER" --location "$GCP_LOCATION" --project "$GCP_PROJECT"
gcloud sql instances describe "$CLOUDSQL_INSTANCE" --project "$GCP_PROJECT"
# Choose the verified instance DNS name from dnsNames and configure its private DNS resolution.
export CLOUDSQL_DNS=YOUR_VERIFIED_INSTANCE_DNS_NAME
export CA_FILE=/path/to/current-cloud-sql-ca-bundle.pem
export DB_URL="jdbc:postgresql://${CLOUDSQL_DNS}:5432/scheduler?sslmode=verify-full&sslrootcert=/etc/postgresql-ca/ca.crt"
```

Then run the common steps. If your instance requires mutual TLS, also mount the client
certificate/private key from Secret and add JDBC sslcert/sslkey parameters; the supplied
base/prod overlay implements server-authenticated TLS, not a client-certificate mount.

## AKS + Azure Database for PostgreSQL Flexible Server

Connect AKS to the server's private network/private DNS, and allow only the required cluster
source ranges. Keep `require_secure_transport` enabled. Use the server FQDN for verified TLS.
Microsoft currently documents DigiCert Global Root G2 and Microsoft RSA Root CA 2017; obtain
both current roots, not an intermediate/leaf certificate, from the provider instructions.
[Azure TLS configuration](https://learn.microsoft.com/en-us/azure/postgresql/security/security-tls-how-to-connect).

```bash
az aks get-credentials --resource-group "$AZURE_RESOURCE_GROUP" --name "$AKS_CLUSTER"
export AZURE_PG_FQDN=$(az postgres flexible-server show --resource-group "$AZURE_RESOURCE_GROUP" \
  --name "$AZURE_PG_SERVER" --query fullyQualifiedDomainName -o tsv)
curl -fsSL 'https://www.microsoft.com/pkiops/certs/Microsoft%20RSA%20Root%20Certificate%20Authority%202017.crt' -o /tmp/microsoft-rsa-root.crt
openssl x509 -inform DER -in /tmp/microsoft-rsa-root.crt -out /tmp/microsoft-rsa-root.pem
curl -fsSL https://cacerts.digicert.com/DigiCertGlobalRootG2.crt.pem -o /tmp/digicert-root.pem
cat /tmp/microsoft-rsa-root.pem /tmp/digicert-root.pem > /tmp/azure-postgres-ca.pem
export CA_FILE=/tmp/azure-postgres-ca.pem
export DB_URL="jdbc:postgresql://${AZURE_PG_FQDN}:5432/scheduler?sslmode=verify-full&sslrootcert=/etc/postgresql-ca/ca.crt"
```

Then run the common steps. Database certificate chains rotate; update the provider root
bundle through the same ConfigMap/rollout path when the provider changes its trusted roots.

## Operations shared by all providers

Flyway runs before Hibernate validation on application startup and serializes migrations
with a DB lock. Multiple new pods can start safely. For stricter release ordering, a separate
migration Job can run Flyway first, followed by app rollout with Flyway disabled; this repo
keeps the startup approach and does not supply a migrate-only app command.

Install/enable metrics-server for CPU HPA and observe its conditions. The default HPA maintains
2–10 workers; increase its maximum only after sizing Postgres connections. The manifest pool
size is 5 per pod, not 50 connections per 50 concurrent runs. Budget `(API pods + max worker pods
+ rollout surge) × pool size`, plus migration/admin headroom. DB capacity, webhook latency,
thread capacity, and target rate limits can each become the bottleneck.

Scrape each pod's /actuator/prometheus endpoint on its internal port. Global backlog/lag are
duplicated across pods: aggregate with max, not sum. Local running counts/counters can be summed.
KEDA/custom-metric autoscaling is a stretch item, not included here. Restrict actuator network
access to the cluster/monitoring path. Add an approved TLS ingress/load balancer for external API
clients; no public Ingress is installed by these manifests.

Keep the 90s pod grace above the 60s scheduler grace and Spring shutdown-phase budget. PDB protects
evictions, while force deletion/crashes can repeat effects under at-least-once delivery. Use
stable business keys and durable idempotency on target systems; run IDs identify attempts.
Network-level egress enforcement is needed where DNS rebinding must be prevented end to end.
