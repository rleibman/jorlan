#!/usr/bin/env bash
# Initializes the Jorlan MariaDB database: creates the database, application user, and grants.
# Run once as the MariaDB root user (or any user with GRANT OPTION).
#
# Usage:
#   ./init-db.sh [--host HOST] [--port PORT] [--root-user USER] [--root-password PASS]
#                [--db-name NAME] [--app-user USER] [--app-password PASS]
#
# Defaults match application.conf dev values. Override via flags or environment variables:
#   JORLAN_DB_HOST, JORLAN_DB_PORT, JORLAN_DB_ROOT_USER, JORLAN_DB_ROOT_PASSWORD,
#   JORLAN_DB_NAME, JORLAN_DB_USER, JORLAN_DB_PASSWORD

set -euo pipefail

# --- Defaults (override via env or flags) ---
DB_HOST="${JORLAN_DB_HOST:-localhost}"
DB_PORT="${JORLAN_DB_PORT:-3306}"
ROOT_USER="${JORLAN_DB_ROOT_USER:-root}"
ROOT_PASSWORD="${JORLAN_DB_ROOT_PASSWORD:-}"
DB_NAME="${JORLAN_DB_NAME:-jorlan}"
APP_USER="${JORLAN_DB_USER:-jorlan}"
APP_PASSWORD="${JORLAN_DB_PASSWORD:-jorlan}"

# --- Argument parsing ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)            DB_HOST="$2";        shift 2 ;;
    --port)            DB_PORT="$2";        shift 2 ;;
    --root-user)       ROOT_USER="$2";      shift 2 ;;
    --root-password)   ROOT_PASSWORD="$2";  shift 2 ;;
    --db-name)         DB_NAME="$2";        shift 2 ;;
    --app-user)        APP_USER="$2";       shift 2 ;;
    --app-password)    APP_PASSWORD="$2";   shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

# --- Build mysql client args ---
MYSQL_ARGS=(-h "$DB_HOST" -P "$DB_PORT" -u "$ROOT_USER")
if [[ -n "$ROOT_PASSWORD" ]]; then
  MYSQL_ARGS+=(-p"$ROOT_PASSWORD")
fi

echo "Connecting to MariaDB at ${DB_HOST}:${DB_PORT} as '${ROOT_USER}'..."

mysql "${MYSQL_ARGS[@]}" <<SQL
-- Create the database if it does not exist
CREATE DATABASE IF NOT EXISTS \`${DB_NAME}\`
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- Create the application user (both localhost and any host for Docker/K8s environments)
CREATE USER IF NOT EXISTS '${APP_USER}'@'localhost' IDENTIFIED BY '${APP_PASSWORD}';
CREATE USER IF NOT EXISTS '${APP_USER}'@'%'         IDENTIFIED BY '${APP_PASSWORD}';

-- Grant all privileges on the jorlan database only
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${APP_USER}'@'localhost';
GRANT ALL PRIVILEGES ON \`${DB_NAME}\`.* TO '${APP_USER}'@'%';

FLUSH PRIVILEGES;
SQL

echo "Database '${DB_NAME}' and user '${APP_USER}' created successfully."
echo "Flyway will run the schema migrations on first server startup."
