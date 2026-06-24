# Jorlan — Installation Guide

> **GitHub**: <https://github.com/rleibman/jorlan>

Jorlan is a self-hosted AI agent runtime. This guide covers server installation, initial database setup, and enabling
optional skills.

---

## Requirements

| Requirement | Version                                        |
|-------------|------------------------------------------------|
| Java (JRE)  | 21+                                            |
| MariaDB     | Must support VECTOR type + VECTOR INDEX (see `server/src/main/resources/sql/V030__memory_vector_store.sql`) |
| OS          | Linux (Debian/Ubuntu) or macOS                 |

---

## 1. Install the Server

### From GitHub Releases (recommended)

Pre-built `.deb` packages are attached to every release at  
<https://github.com/rleibman/jorlan/releases/latest>

```bash
# Install Java (if not already installed)
sudo apt install default-jre-headless

# Download jorlan-server_<version>_all.deb from the Releases page, then:
sudo dpkg -i jorlan-server_<version>_all.deb

# Optional: CLI shell client
sudo dpkg -i jorlan-shell_<version>_all.deb
```

### From Source

```bash
git clone https://github.com/rleibman/jorlan.git
cd jorlan
sbt server/debian:packageBin
sudo dpkg -i server/target/jorlan-server_*.deb
```

---

## 2. Set Up the Database

```bash
# Run the initialisation script (creates the jorlan DB and app user)
sudo /usr/lib/jorlan-server/scripts/init-db.sh \
  --root-password <mariadb-root-pw> \
  --app-password  <app-pw>
```

Edit `/etc/jorlan/server.env` and set at minimum:

```env
JORLAN_DB_PASSWORD=<app-pw>
JORLAN_AUTH_SECRET_KEY=<at-least-32-char-random-string>
```

---

## 3. Start the Server

```bash
sudo systemctl enable jorlan-server
sudo systemctl start  jorlan-server
# Check logs
sudo journalctl -u jorlan-server -f
```

The web UI is served at `http://localhost:8080` by default.

---

## 4. First Login

On first start, Jorlan seeds a default admin user:

| Field    | Value          |
|----------|----------------|
| Email    | `admin@jorlan` |
| Password | `admin`        |

**Change this immediately** via Settings → Users.

---

## 5. Skill Configuration

Skills are enabled via the **Skills** page in the web UI, or by inserting rows directly into the `server_settings`
table. Each configurable skill reads its configuration from a JSON blob stored under a well-known key.

See the individual skill `INSTALL.md` files for configuration details:

| Skill                                         | Config key        | External account required |
|-----------------------------------------------|-------------------|---------------------------|
| [Calculator](calculator/INSTALL.md)           | —                 | No                        |
| [Unit Conversion](unit-conversion/INSTALL.md) | —                 | No                        |
| [Time](time-skill/INSTALL.md)                 | `skill.time`      | No                        |
| [HTTP Fetch](http-fetch/INSTALL.md)           | `skill.httpFetch` | No                        |
| [Weather](weather/INSTALL.md)                 | `skill.weather`   | OpenWeatherMap API key    |
| [Market Data](market-data/INSTALL.md)         | `skill.market`    | Alpha Vantage API key     |
| [Web Search](search/INSTALL.md)               | `skill.search`    | Tavily API key            |
| [Lyrion Music](lyrion/INSTALL.md)             | `skill.lyrion`    | Lyrion Music Server       |
| [Telegram](telegram/INSTALL.md)               | `skill.telegram`  | Telegram Bot Token        |
| [Email (IMAP/SMTP)](email/INSTALL.md)         | `skill.email`     | IMAP/SMTP account         |
| [Google Calendar](google-services/INSTALL.md) | OAuth per-user    | Google Cloud project      |
| [Google Contacts](google-services/INSTALL.md) | OAuth per-user    | Google Cloud project      |
| [Google Drive](google-services/INSTALL.md)    | OAuth per-user    | Google Cloud project      |

Built-in skills (contacts, memory, notify, scheduler, shell, user management, workspace) require no external accounts
and are always available.

---

## 6. Upgrading

```bash
# Download the new .deb from https://github.com/rleibman/jorlan/releases
sudo dpkg -i jorlan-server_<new-version>_all.deb
sudo systemctl restart jorlan-server
```

Flyway database migrations run automatically on startup.

---

## Troubleshooting

| Symptom               | Check                                                                                       |
|-----------------------|---------------------------------------------------------------------------------------------|
| Server won't start    | `journalctl -u jorlan-server -n 50`                                                         |
| DB connection refused | Verify `JORLAN_DB_PASSWORD` in `/etc/jorlan/server.env` and that MariaDB is running         |
| Skill stays disabled  | Check the skill's config JSON is valid; see its `INSTALL.md`                                |
| Web UI blank page     | Check browser console; ensure the web assets were installed (`/usr/lib/jorlan-server/www/`) |
