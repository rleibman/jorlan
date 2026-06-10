#!/usr/bin/env bash
# install-macos.sh — Install Jorlan Server on macOS (non-Homebrew path).
#
# Prerequisites:
#   - Java 21+ on PATH (e.g. brew install openjdk@21)
#   - MariaDB running and accessible
#   - A downloaded jorlan-server-*.tgz tarball in the current directory
#
# What this script does:
#   1. Extracts the tarball to /opt/jorlan-server
#   2. Creates a 'jorlan' system user and group
#   3. Creates /var/log/jorlan-server
#   4. Installs /etc/jorlan/server.env (env template, not overwritten if exists)
#   5. Installs /Library/LaunchDaemons/io.jorlan.server.plist
#   6. Offers to load the daemon (requires sudo)
#
# Usage:
#   sudo ./install-macos.sh jorlan-server-0.1.0.tgz
#
# To use Homebrew instead (recommended):
#   brew tap rleibman/jorlan
#   brew install jorlan
#   brew services start jorlan

set -euo pipefail

INSTALL_DIR="/opt/jorlan-server"
LOG_DIR="/var/log/jorlan-server"
ETC_DIR="/etc/jorlan"
PLIST_DST="/Library/LaunchDaemons/io.jorlan.server.plist"
DAEMON_USER="jorlan"
DAEMON_GROUP="jorlan"

if [[ $EUID -ne 0 ]]; then
  echo "ERROR: This script must be run as root (sudo)." >&2
  exit 1
fi

TARBALL="${1:-}"
if [[ -z "$TARBALL" || ! -f "$TARBALL" ]]; then
  echo "Usage: sudo $0 <jorlan-server-*.tgz>" >&2
  exit 1
fi

echo "==> Extracting $TARBALL to $INSTALL_DIR ..."
rm -rf "$INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
tar -xzf "$TARBALL" --strip-components=1 -C "$INSTALL_DIR"
chmod +x "$INSTALL_DIR/bin/jorlan-server"

echo "==> Creating system user '$DAEMON_USER' ..."
if ! dscl . -read /Groups/"$DAEMON_GROUP" &>/dev/null; then
  NEXT_GID=$(dscl . -list /Groups PrimaryGroupID | awk '{print $2}' | sort -n | tail -1)
  NEXT_GID=$((NEXT_GID + 1))
  dscl . -create /Groups/"$DAEMON_GROUP"
  dscl . -create /Groups/"$DAEMON_GROUP" PrimaryGroupID "$NEXT_GID"
  dscl . -create /Groups/"$DAEMON_GROUP" RealName "Jorlan Server"
fi
if ! dscl . -read /Users/"$DAEMON_USER" &>/dev/null; then
  NEXT_UID=$(dscl . -list /Users UniqueID | awk '{print $2}' | sort -n | tail -1)
  NEXT_UID=$((NEXT_UID + 1))
  dscl . -create /Users/"$DAEMON_USER"
  dscl . -create /Users/"$DAEMON_USER" UniqueID "$NEXT_UID"
  dscl . -create /Users/"$DAEMON_USER" PrimaryGroupID "$(dscl . -read /Groups/"$DAEMON_GROUP" PrimaryGroupID | awk '{print $2}')"
  dscl . -create /Users/"$DAEMON_USER" UserShell /usr/bin/false
  dscl . -create /Users/"$DAEMON_USER" RealName "Jorlan Server"
  dscl . -create /Users/"$DAEMON_USER" NFSHomeDirectory /var/empty
fi

echo "==> Creating log directory $LOG_DIR ..."
mkdir -p "$LOG_DIR/conversations"
chown -R "$DAEMON_USER:$DAEMON_GROUP" "$LOG_DIR"
chmod 750 "$LOG_DIR" "$LOG_DIR/conversations"

echo "==> Installing env template to $ETC_DIR/server.env ..."
mkdir -p "$ETC_DIR"
if [[ ! -f "$ETC_DIR/server.env" ]]; then
  cp "$INSTALL_DIR/conf/server.env" "$ETC_DIR/server.env"
  chown root:"$DAEMON_GROUP" "$ETC_DIR/server.env"
  chmod 640 "$ETC_DIR/server.env"
  echo ""
  echo "  *** IMPORTANT: Edit $ETC_DIR/server.env before starting the server. ***"
  echo ""
else
  echo "  $ETC_DIR/server.env already exists — not overwritten."
fi

echo "==> Installing LaunchDaemon plist to $PLIST_DST ..."
cp "$INSTALL_DIR/launchd/io.jorlan.server.plist" "$PLIST_DST"
chmod 644 "$PLIST_DST"
chown root:wheel "$PLIST_DST"

echo ""
echo "Installation complete."
echo ""
echo "Next steps:"
echo "  1. Run the database setup:  $INSTALL_DIR/scripts/init-db.sh"
echo "  2. Edit the env file:       $ETC_DIR/server.env"
echo "  3. Load the daemon:         sudo launchctl load $PLIST_DST"
echo "  4. Check status:            launchctl list io.jorlan.server"
echo ""
echo "To uninstall:"
echo "  sudo launchctl unload $PLIST_DST"
echo "  sudo rm -rf $INSTALL_DIR $PLIST_DST $LOG_DIR"
