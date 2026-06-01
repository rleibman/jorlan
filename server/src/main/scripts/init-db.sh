#!/usr/bin/env bash
# init-db.sh — Bootstrap the Jorlan MariaDB database and application user.
#
# Run ONCE before the first server start. Safe to re-run (all statements use
# IF NOT EXISTS / CREATE OR REPLACE semantics). Requires temporary MySQL root
# (or equivalent admin) credentials.
#
# Usage — environment variables:
#   MYSQL_HOST          MariaDB host            (default: 127.0.0.1)
#   MYSQL_PORT          MariaDB port            (default: 3306)
#   MYSQL_ROOT_USER     Admin username          (default: root)
#   MYSQL_ROOT_PASSWORD Admin password          (required — no default)
#   JORLAN_DB_NAME      Database to create      (default: jorlan)
#   JORLAN_DB_USER      Application username    (default: jorlan)
#   JORLAN_DB_PASSWORD  Application password    (required — no default)
#   JORLAN_DB_HOST      Host granted to app user (default: localhost)
#
# Usage — flags (override env vars):
#   --host          MariaDB host
#   --port          MariaDB port
#   --root-user     Admin username
#   --root-password Admin password
#   --db-name       Database name
#   --app-user      Application username
#   --app-password  Application password
#   --app-host      Host granted to app user
#
# Example:
#   MYSQL_ROOT_PASSWORD=secret JORLAN_DB_PASSWORD=changeme ./init-db.sh

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_ROOT_USER="${MYSQL_ROOT_USER:-root}"
MYSQL_ROOT_PASSWORD="${MYSQL_ROOT_PASSWORD:-}"
JORLAN_DB_NAME="${JORLAN_DB_NAME:-jorlan}"
JORLAN_DB_USER="${JORLAN_DB_USER:-jorlan}"
JORLAN_DB_PASSWORD="${JORLAN_DB_PASSWORD:-}"
JORLAN_DB_HOST="${JORLAN_DB_HOST:-localhost}"

# ── Parse flags ───────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)          MYSQL_HOST="$2";          shift 2 ;;
    --port)          MYSQL_PORT="$2";          shift 2 ;;
    --root-user)     MYSQL_ROOT_USER="$2";     shift 2 ;;
    --root-password) MYSQL_ROOT_PASSWORD="$2"; shift 2 ;;
    --db-name)       JORLAN_DB_NAME="$2";      shift 2 ;;
    --app-user)      JORLAN_DB_USER="$2";      shift 2 ;;
    --app-password)  JORLAN_DB_PASSWORD="$2";  shift 2 ;;
    --app-host)      JORLAN_DB_HOST="$2";      shift 2 ;;
    --help|-h)
      sed -n '/^# /p' "$0" | sed 's/^# //'
      exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# ── Validate required values ───────────────────────────────────────────────────
errors=()
[[ -z "$MYSQL_ROOT_PASSWORD" ]] && errors+=("MYSQL_ROOT_PASSWORD (or --root-password) is required")
[[ -z "$JORLAN_DB_PASSWORD"  ]] && errors+=("JORLAN_DB_PASSWORD (or --app-password) is required")
if [[ ${#errors[@]} -gt 0 ]]; then
  echo "ERROR: Missing required values:" >&2
  for e in "${errors[@]}"; do echo "  - $e" >&2; done
  echo "" >&2
  echo "Run with --help for usage." >&2
  exit 1
fi

# ── Check mysql/mariadb client ─────────────────────────────────────────────────
if command -v mariadb &>/dev/null; then
  MYSQL_CMD="mariadb"
elif command -v mysql &>/dev/null; then
  MYSQL_CMD="mysql"
else
  echo "ERROR: Neither 'mariadb' nor 'mysql' client found on PATH." >&2
  echo "Install MariaDB client tools (e.g. 'apt install mariadb-client')." >&2
  exit 1
fi

mysql_exec() {
  "$MYSQL_CMD" \
    --host="$MYSQL_HOST" \
    --port="$MYSQL_PORT" \
    --user="$MYSQL_ROOT_USER" \
    --password="$MYSQL_ROOT_PASSWORD" \
    --batch \
    --silent \
    -e "$1"
}

# ── Run ────────────────────────────────────────────────────────────────────────
echo "Connecting to MariaDB at ${MYSQL_HOST}:${MYSQL_PORT} as ${MYSQL_ROOT_USER}..."

mysql_exec "SELECT 1" &>/dev/null || {
  echo "ERROR: Cannot connect to MariaDB at ${MYSQL_HOST}:${MYSQL_PORT} as ${MYSQL_ROOT_USER}." >&2
  echo "Check your credentials and that the server is running." >&2
  exit 1
}

echo "Creating database '${JORLAN_DB_NAME}' (if not exists)..."
mysql_exec "CREATE DATABASE IF NOT EXISTS \`${JORLAN_DB_NAME}\`
            DEFAULT CHARACTER SET utf8mb4
            DEFAULT COLLATE utf8mb4_unicode_ci;"

echo "Creating application user '${JORLAN_DB_USER}'@'${JORLAN_DB_HOST}' (if not exists)..."
mysql_exec "CREATE USER IF NOT EXISTS '${JORLAN_DB_USER}'@'${JORLAN_DB_HOST}'
            IDENTIFIED BY '${JORLAN_DB_PASSWORD}';"

# Update password in case it changed since the user was first created.
mysql_exec "ALTER USER '${JORLAN_DB_USER}'@'${JORLAN_DB_HOST}'
            IDENTIFIED BY '${JORLAN_DB_PASSWORD}';"

echo "Granting privileges on '${JORLAN_DB_NAME}' to '${JORLAN_DB_USER}'@'${JORLAN_DB_HOST}'..."
mysql_exec "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER,
            CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE, CREATE VIEW,
            SHOW VIEW, CREATE ROUTINE, ALTER ROUTINE, EVENT, TRIGGER
            ON \`${JORLAN_DB_NAME}\`.*
            TO '${JORLAN_DB_USER}'@'${JORLAN_DB_HOST}';"

mysql_exec "FLUSH PRIVILEGES;"

echo ""
echo "Done. Database '${JORLAN_DB_NAME}' and user '${JORLAN_DB_USER}'@'${JORLAN_DB_HOST}' are ready."
echo ""
echo "Set these in your environment (or .env file) before starting Jorlan:"
echo "  JORLAN_DB_URL=jdbc:mariadb://${MYSQL_HOST}:${MYSQL_PORT}/${JORLAN_DB_NAME}"
echo "  JORLAN_DB_USER=${JORLAN_DB_USER}"
echo "  JORLAN_DB_PASSWORD=<the password you provided>"
