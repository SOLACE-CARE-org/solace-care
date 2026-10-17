# Kubernetes and Argo CD

Manifests for the deployed environments: one managed Kubernetes cluster each for dev, staging and prod (System Design, Infrastructure). **They are written and validated but not applied**: hosting is still an open question (System Design, Open questions), and nothing here depends on a particular provider.

```text
infra/k8s/base/               every Solace workload, its Service, autoscaler, disruption budget,
                              network policies, ingress and secrets (from Vault)
infra/k8s/overlays/<env>/     namespace, host names, image tags, sizes per environment
infra/argocd/                 the Argo CD project and one Application per environment
```

```sh
kubectl kustomize infra/k8s/overlays/dev      # see what dev would get
```

CI renders all three overlays on every pull request and checks them against the Kubernetes and CRD schemas (kubeconform) and Trivy's Kubernetes security checks (`.github/workflows/ci.yml`, job `manifests`).

## What runs where

| Workload | Port(s) | Replicas | Notes |
| --- | --- | --- | --- |
| gateway | 8080 | HPA 2–6 (dev 1–2) | The only API entry point; `api.<domain>` |
| core | 8080 | HPA 2–6 (dev 1–2) | Calls tracking and ai over gRPC through headless Services, so calls balance across pods |
| tracking, ai | 8080, gRPC 9090 | HPA 2–6 (dev 1–2) | |
| admin | 8080 | 2 (dev 1) | nginx; `admin.<domain>`; settings from `admin-settings` |
| keycloak | 8080, 9000 health, 7800 cluster | 2 (dev 1) | `id.<domain>`, only `/realms/` and `/resources/` are public |

Every pod runs as a non-root user with a read-only root filesystem, no privilege escalation, every Linux capability dropped and the default seccomp profile; each namespace enforces Kubernetes' `restricted` Pod Security level. Memory has limits; CPU has requests only, because CPU limits throttle the JVM during start-up and garbage collection.

Network policies deny all inbound traffic except: ingress controller to gateway, admin and Keycloak; gateway to the three services; Core to Tracking and AI gRPC; Core to Keycloak (service tokens); Keycloak to Keycloak. Outbound traffic is open because the databases and brokers live outside the namespace.

## What the platform must provide

These are not in the manifests, because they depend on hosting:

| Needed | Default address in `solace-platform` | System Design |
| --- | --- | --- |
| PostgreSQL 16, one database per service plus Keycloak's; pgvector in `ai` | in each service's secret | PITR, RPO 15 min, RTO 4 h |
| Kafka (+ schema registry later) | `kafka-bootstrap.kafka:9092` | Strimzi or managed |
| Redis | `redis.redis` | |
| OpenTelemetry collector feeding Tempo, Prometheus, Loki | `otel-collector.observability:4318` | |
| Vault and the External Secrets Operator, with a `ClusterSecretStore` named `vault` scoped to the environment | | Secrets; the keys each service needs are listed in `base/secrets.yaml` |
| An ingress controller whose namespace has the label `solace.care/ingress: "true"`, behind a WAF/CDN, and a TLS certificate in `solace-tls` | | Gateway row |
| S3-compatible storage and LiveKit | | Not used until Phase 1 |

## Releasing

CI publishes every commit on main as `ghcr.io/<owner>/solace-<name>:<first 12 characters of the commit>`. A release is a pull request that changes `newTag` in an overlay. Argo CD syncs dev and staging by itself; prod shows the difference and waits for someone to press Sync. Deleting the prod Application never deletes what it deployed.

## Placeholders and open items

- **Host names** (`*.solace.example`), the **GitHub owner** in image names and in `infra/argocd` (`solace-care`), and the **Argo CD cluster names** (`solace-dev`, `solace-staging`, `solace-prod`) are placeholders until the domain, the GitHub organisation and the clusters exist.
- **mTLS between services** (System Design, Security) needs a service mesh or SPIFFE certificates; which one depends on hosting. Until then, the network policies limit who can call whom, and every call still carries a scoped client-credentials token.
- **Realms in deployed environments.** `infra/keycloak/realms` holds development secrets and is for local use only. Deployed realms need their own client secrets (from Vault) and redirect URIs for the real host names, applied by a realm-config tool or the Keycloak Operator. To be decided with hosting.
- **Sign-in codes in dev** go to Keycloak's log (`SOLACE_DEV_CODE_SENDER=enabled` in the dev overlay only) until the SMS provider is connected in Phase 1. Staging and prod have no such switch.
