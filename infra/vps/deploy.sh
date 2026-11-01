#!/usr/bin/env bash
# Deploys a branch from GitHub: pulls it, builds the images here, tags them with the commit and
# restarts what changed. deploy.sh [branch]; without one, the branch in /opt/solace/.branch.
set -euo pipefail
ROOT=/opt/solace
SRC=$ROOT/src
BRANCH=${1:-$(cat "$ROOT/.branch")}

git -C "$SRC" fetch --quiet origin "$BRANCH"
git -C "$SRC" checkout --quiet -B "$BRANCH" "origin/$BRANCH"
TAG=$(git -C "$SRC" rev-parse --short=12 HEAD)
echo "deploying $BRANCH at $TAG"

# Build tools run in containers, as this user, with their caches outside the checkout.
mkdir -p "$ROOT/cache"
in_container() {
  local image=$1
  shift
  docker run --rm --user "$(id -u):$(id -g)" --memory 3g \
    -v "$SRC:/src" -v "$ROOT/cache:/cache" -e HOME=/cache -e GRADLE_USER_HOME=/cache/gradle \
    -w /src "$image" "$@"
}

in_container eclipse-temurin:21.0.8_9-jdk-noble \
  sh -c 'cd backend && sh ./gradlew bootJar :keycloak:otp-authenticator:jar --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1536m'
in_container node:22.22.3-slim \
  sh -c 'npx --yes pnpm@10.17.0 install --frozen-lockfile && npx --yes pnpm@10.17.0 --filter @solace/admin build'

for service in core tracking ai gateway; do
  docker build --quiet --build-arg SERVICE="$service" -t "solace-$service:$TAG" "$SRC/backend"
done
docker build --quiet -t "solace-admin:$TAG" "$SRC/admin"
docker build --quiet -f "$SRC/infra/keycloak/Dockerfile" -t "solace-keycloak:$TAG" "$SRC"

cp "$SRC"/infra/vps/{docker-compose.yml,deploy.sh,backup.sh,copy-turn-cert.sh} "$ROOT/"
mkdir -p "$ROOT/realms" "$ROOT/postgres-init"
cp "$SRC"/infra/keycloak/realms/*.json "$ROOT/realms/"
cp "$SRC"/infra/postgres/init/* "$ROOT/postgres-init/"
sed -i "s/^SOLACE_TAG=.*/SOLACE_TAG=$TAG/" "$ROOT/.env"
echo "$BRANCH" > "$ROOT/.branch"

cd "$ROOT"
docker compose up -d --remove-orphans
docker image prune -f > /dev/null
echo "deployed $TAG"
