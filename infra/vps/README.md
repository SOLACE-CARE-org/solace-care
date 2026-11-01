# One-server deployment

Every Solace service on one VPS, with Docker Compose behind Caddy, for testing and small pilots. `docs/deployment-guide.md` describes the full Kubernetes deployment; this is the small version of it.

**This is for testing with no real people.** Payments use the sandbox provider and messages, sign-in codes included, go to Core's log. Before any real person uses it, set up Notch Pay and real SMS and email senders, and remove `SOLACE_NOTIFICATIONS_SENDER=logging` and `SOLACE_NOTIFICATIONS_PUSH_SENDER=logging` from `docker-compose.yml`.

## What it leaves out

| Left out | Effect |
| --- | --- |
| Kubernetes | One copy of each service, so a restart or a server failure is an outage |
| Vault | Secrets are in `/opt/solace/.env`, readable only by root and the deploy user |
| Grafana, Prometheus, Loki, Tempo | Logs are in Docker: `docker compose logs <service>`. Nothing raises an alert. |
| LiveKit Egress | The scribe can't record, so it stays off |
| Schema registry | Not used by the services yet |

## Layout on the server

Everything is in `/opt/solace`: this folder's files, `.env`, `livekit.yaml`, `realms/`, `postgres-init/`, `turn-certs/` and `backups/`.

The code comes from GitHub. `deploy.sh` pulls the branch into `/opt/solace/src`, builds the images on the server, tags them with the commit and restarts what changed:

```sh
/opt/solace/deploy.sh                # the branch deployed last
/opt/solace/deploy.sh <branch>       # another branch
```

A build needs about 2 GB of memory, so run it when nobody is testing. The server only needs read access to the repository.

## Everyday commands

```sh
cd /opt/solace
docker compose ps
docker compose logs -f core
docker compose restart core
docker stats --no-stream
```

**A sign-in code for testing:**

```sh
docker compose logs core | grep -i code | tail
```

## Keys

Back up `.env` somewhere safe and away from this server. `SOLACE_HEALTH_CHECK_KEY`, `SOLACE_SAFETY_PLAN_KEY` and `SOLACE_AI_NOTES_KEY` seal stored data, and if they're lost that data can't be read again. `make-secrets.sh` refuses to run twice for the same reason.
