#!/bin/bash
# Creates one database and one owner role per service, plus one for Keycloak.
# Each role can connect only to its own database: no service reads another's tables (D3).
# Runs once, when the postgres-data volume is first created.
set -euo pipefail

create_db() {
  local name="$1" password="$2"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
    -v name="$name" -v password="$password" <<'SQL'
CREATE ROLE :"name" LOGIN PASSWORD :'password';
CREATE DATABASE :"name" OWNER :"name";
REVOKE ALL ON DATABASE :"name" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"name" TO :"name";
SQL
}

create_db core "$CORE_DB_PASSWORD"
create_db tracking "$TRACKING_DB_PASSWORD"
create_db ai "$AI_DB_PASSWORD"
create_db keycloak "$KEYCLOAK_DB_PASSWORD"

# pgvector for the AI service. Created here because the extension needs a superuser.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname ai -c 'CREATE EXTENSION IF NOT EXISTS vector;'
