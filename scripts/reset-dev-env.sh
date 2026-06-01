#!/usr/bin/env bash
# reset-dev-env.sh — Reset the Jorlan development environment for fresh manual testing.
#
# What this script does:
#   1. Kills any running Jorlan server or shell processes
#   2. Drops and recreates the Jorlan database (Flyway will re-run all migrations on next server start)
#   3. Backs up and removes the shell config file  (~/.jorlan/jorlan-shell.json)
#   4. Backs up and removes legacy shell config    (~/.jorlan/jorlan.json)
#   5. Backs up and truncates shell log files      (~/.jorlan/shell*.log)
#
# After running this script:
#   - Start the server: it will print a SETUP TOKEN and wait for initialization
#   - Start the shell: it will launch the FirstRunWizard
#
# Usage:
#   ./scripts/reset-dev-env.sh [options]
#
# Options:
#   --db-only        Only reset the database; leave config files intact
#   --config-only    Only reset config files; leave the database intact
#   --no-backup      Skip config file backups (destructive — no recovery)
#   --yes            Skip the confirmation prompt
#   --help / -h      Show this help and exit
#
# Database credentials are read from the environment (matching application.conf conventions):
#   JORLAN_DB_URL      (default: jdbc:mariadb://localhost:3306/jorlan)
#   JORLAN_DB_USER     (default: jorlan)
#   JORLAN_DB_PASSWORD (default: jorlan)
#
# To drop and recreate using a privileged user instead of the application user:
#   MYSQL_ROOT_USER=root MYSQL_ROOT_PASSWORD=secret ./scripts/reset-dev-env.sh
#
# NOTE: This script is for local development only. Never run it against a production database.

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────────────
RESET_DB=true
RESET_CONFIG=true
BACKUP=true
SKIP_CONFIRM=false

# ── Parse flags ───────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --db-only)      RESET_CONFIG=false; shift ;;
    --config-only)  RESET_DB=false;     shift ;;
    --no-backup)    BACKUP=false;        shift ;;
    --yes|-y)       SKIP_CONFIRM=true;  shift ;;
    --help|-h)
      # Print the leading doc-comment block only (stop at first blank comment line after content)
      awk '/^#!/ { next } /^#/ { print substr($0, 3) } /^[^#]/ { exit }' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $1  (run with --help for usage)" >&2
      exit 1
      ;;
  esac
done

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; RESET='\033[0m'

info()    { echo -e "${CYAN}▶${RESET}  $*"; }
ok()      { echo -e "${GREEN}✔${RESET}  $*"; }
warn()    { echo -e "${YELLOW}⚠${RESET}  $*"; }
section() { echo; echo -e "${YELLOW}━━ $* ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"; }

# ── Confirmation ──────────────────────────────────────────────────────────────
section "Jorlan Dev Environment Reset"
echo
if [[ "$RESET_DB" == true ]];     then echo "  • Drop and recreate the Jorlan MariaDB database"; fi
if [[ "$RESET_CONFIG" == true ]]; then echo "  • Back up and remove ~/.jorlan/jorlan-shell.json and ~/.jorlan/jorlan.json"; fi
echo
if [[ "$SKIP_CONFIRM" == false ]]; then
  read -r -p "$(echo -e "${RED}This is destructive. Continue?${RESET} [y/N] ")" answer
  if [[ "$answer" != "y" && "$answer" != "Y" ]]; then
    echo "Aborted."
    exit 0
  fi
fi

# ── Step 1: Kill running Jorlan processes ─────────────────────────────────────
section "Stopping Jorlan processes"

if pgrep -f "jorlan.Jorlan" > /dev/null 2>&1; then
  info "Sending SIGTERM to Jorlan server process(es)..."
  pkill -SIGTERM -f "jorlan.Jorlan" || true
  sleep 2
  if pgrep -f "jorlan.Jorlan" > /dev/null 2>&1; then
    warn "Process did not exit; sending SIGKILL..."
    pkill -SIGKILL -f "jorlan.Jorlan" || true
  fi
  ok "Jorlan server stopped."
else
  info "No running Jorlan server found — skipping."
fi

if pgrep -f "jorlan.shell.JorlanShell" > /dev/null 2>&1; then
  info "Sending SIGTERM to Jorlan shell process(es)..."
  pkill -SIGTERM -f "jorlan.shell.JorlanShell" || true
  sleep 1
  ok "Jorlan shell stopped."
else
  info "No running Jorlan shell found — skipping."
fi

# ── Step 2: Reset database ────────────────────────────────────────────────────
if [[ "$RESET_DB" == true ]]; then
  section "Resetting database"

  # Parse host/port/dbname from JORLAN_DB_URL  (jdbc:mariadb://host:port/dbname)
  DB_URL="${JORLAN_DB_URL:-jdbc:mariadb://localhost:3306/jorlan}"
  DB_HOST=$(echo "$DB_URL" | sed -E 's|jdbc:mariadb://([^:/]+).*|\1|')
  DB_PORT=$(echo "$DB_URL" | sed -E 's|jdbc:mariadb://[^:]+:([0-9]+)/.*|\1|')
  DB_NAME=$(echo "$DB_URL" | sed -E 's|jdbc:mariadb://[^/]+/([^?]+).*|\1|')

  # Use privileged credentials if provided, otherwise fall back to app user
  MYSQL_USER="${MYSQL_ROOT_USER:-${JORLAN_DB_USER:-jorlan}}"
  MYSQL_PASS="${MYSQL_ROOT_PASSWORD:-${JORLAN_DB_PASSWORD:-jorlan}}"

  info "Database: ${DB_NAME}  @  ${DB_HOST}:${DB_PORT}"
  info "Connecting as user: ${MYSQL_USER}"

  # Pick the client binary
  if command -v mariadb &>/dev/null; then
    MYSQL_CMD="mariadb"
  elif command -v mysql &>/dev/null; then
    MYSQL_CMD="mysql"
  else
    echo -e "${RED}ERROR:${RESET} Neither 'mariadb' nor 'mysql' client found on PATH." >&2
    echo "Install with: apt install mariadb-client" >&2
    exit 1
  fi

  mysql_exec() {
    "$MYSQL_CMD" \
      --host="$DB_HOST" \
      --port="$DB_PORT" \
      --user="$MYSQL_USER" \
      --password="$MYSQL_PASS" \
      --batch \
      --silent \
      -e "$1"
  }

  # Verify connectivity
  mysql_exec "SELECT 1" &>/dev/null || {
    echo -e "${RED}ERROR:${RESET} Cannot connect to MariaDB at ${DB_HOST}:${DB_PORT} as ${MYSQL_USER}." >&2
    echo "Check credentials and that the server is running." >&2
    echo "Set MYSQL_ROOT_USER / MYSQL_ROOT_PASSWORD to use a privileged account." >&2
    exit 1
  }

  info "Dropping database '${DB_NAME}'..."
  mysql_exec "DROP DATABASE IF EXISTS \`${DB_NAME}\`;"

  info "Recreating database '${DB_NAME}'..."
  mysql_exec "CREATE DATABASE \`${DB_NAME}\`
              DEFAULT CHARACTER SET utf8mb4
              DEFAULT COLLATE utf8mb4_unicode_ci;"

  # Re-grant the application user if they exist and we used a privileged account
  APP_USER="${JORLAN_DB_USER:-jorlan}"
  APP_PASS="${JORLAN_DB_PASSWORD:-jorlan}"
  APP_HOST="localhost"
  if [[ "$MYSQL_USER" != "$APP_USER" ]]; then
    info "Re-granting privileges to '${APP_USER}'@'${APP_HOST}'..."
    mysql_exec "GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, INDEX, ALTER,
                CREATE TEMPORARY TABLES, LOCK TABLES, EXECUTE, CREATE VIEW,
                SHOW VIEW, CREATE ROUTINE, ALTER ROUTINE, EVENT, TRIGGER
                ON \`${DB_NAME}\`.*
                TO '${APP_USER}'@'${APP_HOST}';" 2>/dev/null || \
      warn "Grant failed — '${APP_USER}'@'${APP_HOST}' may not exist. Run init-db.sh if needed."
    mysql_exec "FLUSH PRIVILEGES;" 2>/dev/null || true
  fi

  ok "Database reset complete. Flyway will re-run all migrations on next server start."
fi

# ── Step 3: Reset shell config files ─────────────────────────────────────────
if [[ "$RESET_CONFIG" == true ]]; then
  section "Resetting shell config files"

  JORLAN_DIR="${HOME}/.jorlan"
  TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
  BACKUP_DIR="${JORLAN_DIR}/backups/${TIMESTAMP}"

  reset_file() {
    local file="$1"
    local label="$2"
    if [[ -f "$file" ]]; then
      if [[ "$BACKUP" == true ]]; then
        mkdir -p "$BACKUP_DIR"
        cp "$file" "${BACKUP_DIR}/$(basename "$file")"
        ok "Backed up ${label} → ${BACKUP_DIR}/$(basename "$file")"
      fi
      rm -f "$file"
      ok "Removed ${label}."
    else
      info "${label} not found — skipping."
    fi
  }

  reset_file "${JORLAN_DIR}/jorlan-shell.json" "jorlan-shell.json"
  reset_file "${JORLAN_DIR}/jorlan.json"       "jorlan.json (legacy)"

  # Truncate (not delete) log files so the file handle stays valid if the shell is open
  for log_file in "${JORLAN_DIR}"/shell*.log; do
    if [[ -f "$log_file" ]]; then
      if [[ "$BACKUP" == true ]]; then
        mkdir -p "$BACKUP_DIR"
        cp "$log_file" "${BACKUP_DIR}/$(basename "$log_file")" 2>/dev/null || true
      fi
      : > "$log_file"
      ok "Truncated $(basename "$log_file")."
    fi
  done

  if [[ "$BACKUP" == true && -d "$BACKUP_DIR" ]]; then
    info "All backups saved to: ${BACKUP_DIR}"
  fi
fi

# ── Done ──────────────────────────────────────────────────────────────────────
section "Reset complete"
echo
echo "Next steps:"
echo "  1. Source your .env file:    source .env"
echo "  2. Start the server:         sbt 'server/run'"
echo "     The server will print a SETUP TOKEN — copy it."
echo "  3. Start the shell:          sbt 'shell/run'"
echo "     The FirstRunWizard will ask for the token, server name, and admin credentials."
echo
