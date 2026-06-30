# Jorlan — MCP Server Integration Guide

Jorlan can act as a client to any **stdio** or **HTTP** MCP (Model Context Protocol) server.
Once registered, MCP server tools are surfaced to agents exactly like built-in skills —
they appear in the Skill Registry and can be invoked through the normal ReAct loop.

> **Admin UI path**: Settings → MCP Servers

---

## How MCP Servers Work in Jorlan

1. You register an MCP server (command + args + env vars) via the Admin UI or GraphQL mutation.
2. Jorlan spawns the server as a subprocess (stdio) or connects to it (HTTP) on startup.
3. The server's tool catalogue is fetched and exposed to agents as a skill namespace.
4. Reload is required whenever you add, remove, or change a server configuration.

---

## Transport Types

| Transport | When to use | Required fields |
|-----------|-------------|-----------------|
| `Stdio`   | Any MCP server that speaks stdio (most npm packages, local binaries) | `command`, `args` |
| `Http`    | Remote MCP servers exposing an HTTP endpoint | `url` |

---

## Required Capabilities

| Capability | Grants |
|------------|--------|
| `admin.settings` | Register, update, and delete MCP server configurations |
| `admin.mcp.reload` | Trigger a live reload of all registered MCP servers |

These are automatically granted to the admin user at startup.

---

## Environment Variables

Environment variables are passed to the subprocess at launch. Common uses:

- API keys and tokens for the upstream service
- Account identifiers (team IDs, workspace slugs)
- Debug/log level flags for the MCP server process

Variables are stored in the `mcpServer.envVars` column and injected at subprocess spawn
time. They are visible to all Jorlan admins — do not store secrets that should be
per-user or per-session.

---

## GraphQL API

### Queries
- `mcpServers: [McpServer!]` — list all registered servers (requires `admin.settings`)

### Mutations
- `upsertMcpServer(input: McpServerInput!): McpServer` — create or update a server config
- `deleteMcpServer(id: McpServerId!): Boolean` — remove a server config
- `reloadMcpServers: Boolean` — live-reload all server connections (requires `admin.mcp.reload`)

---

## Adding a Server via the Admin UI

1. Navigate to **Settings → MCP Servers**.
2. Click **Add Server**.
3. Fill in the form:

   | Field     | Description |
   |-----------|-------------|
   | Name      | Short identifier (used as tool namespace prefix) |
   | Transport | `Stdio` or `Http` |
   | Command   | (stdio only) Executable to run, e.g. `npx` |
   | Args      | (stdio only) Comma-separated arguments |
   | URL       | (http only) Server base URL |
   | Env vars  | Key=value pairs for the subprocess environment |
   | Enabled   | Uncheck to disable without deleting |

4. Click **Save**, then click **Reload**.

---

## Adding a Server via GraphQL

```graphql
mutation {
  upsertMcpServer(input: {
    name:      "my-server"
    transport: Stdio
    command:   "npx"
    args:      ["-y", "my-mcp-package"]
    envVars:   [{ key: "API_KEY", value: "..." }]
    enabled:   true
  }) {
    id
    name
  }
}
```

---

## Verifying Registration

After reload, the server's tools appear in **Settings → Skill Registry** under the
server's name as a namespace prefix. If they don't appear, check the Jorlan server
logs for subprocess startup errors.

---

## General Troubleshooting

| Symptom | Check |
|---------|-------|
| Tools don't appear after reload | Check Jorlan server logs for subprocess errors |
| `command` not found | Verify the binary is on `$PATH` for the OS user running Jorlan, or use the full path |
| Subprocess exits immediately | Run the command manually in a terminal to see the error output |
| HTTP server unreachable | Confirm the URL is reachable from the Jorlan host; check firewall / TLS settings |
| Permission denied | Ensure the calling user has `admin.mcp.reload` before triggering reload |

---

## Example: OurGroceries MCP Server

[OurGroceries](https://www.ourgroceries.com/) is a shared grocery-list app.
This example wires the [`@sergib/ourgroceries-mcp`](https://github.com/sargue/ourgroceries-mcp)
server (v2.1.0+) so Jorlan agents can read and manage grocery lists.

### Prerequisites

- Node.js 18+ and `npx` available on the machine running Jorlan
- An [OurGroceries account](https://www.ourgroceries.com/)

### Tools provided

| Tool | Description |
|------|-------------|
| List shopping lists | View all lists with item counts |
| Read items | Retrieve active or crossed-off items with optional filtering |
| Add item | Add an item to a named list |
| Remove item | Remove an item from a list |
| Update item | Change name, category, notes, or star rating |
| Cross off / uncross | Mark items as done or restore them |
| List categories | View available categories for the account |
| Resolve item | Convert natural language to known item/list values |

### Step 1 — Authenticate (one-time)

Run this on the **same machine** that runs the Jorlan server, as the **same OS user**:

```bash
npx -y @sergib/ourgroceries-mcp login
```

Enter your OurGroceries email and password when prompted.
Credentials are stored in `~/.config/ourgroceries-mcp/config.json`.

> **For daemon / headless installs**: if Jorlan runs as a dedicated system user
> (e.g. `jorlan`), run the login command as that user:
> ```bash
> sudo -u jorlan npx -y @sergib/ourgroceries-mcp login
> ```

### Step 2 — Register in Jorlan

| Field     | Value |
|-----------|-------|
| Name      | `ourgroceries` |
| Transport | `Stdio` |
| Command   | `npx` |
| Args      | `-y, @sergib/ourgroceries-mcp` |
| Env vars  | *(leave empty — credentials come from the config file)* |
| Enabled   | ✓ |

**Alternative**: if you prefer explicit credentials over the config file, extract
`OURGROCERIES_AUTH_COOKIE` and `OURGROCERIES_TEAM_ID` from
`~/.config/ourgroceries-mcp/config.json` after login and supply them as env vars.

### Step 3 — Reload and verify

Click **Reload** on the MCP Servers page (or call `reloadMcpServers` via GraphQL).
OurGroceries tools should now appear in **Settings → Skill Registry** under `ourgroceries`.

### Using OurGroceries in an Agent Session

Once registered, agents respond to natural-language instructions:

- *"Add milk and eggs to the Costco list."*
- *"What's on the weekly shopping list?"*
- *"Cross off everything on the Trader Joe's list."*

### OurGroceries troubleshooting

| Symptom | Check |
|---------|-------|
| Authentication errors | Re-run `npx -y @sergib/ourgroceries-mcp login` as the correct OS user |
| `config.json` not found | Jorlan is running as a different user than the one that logged in; use env-var credentials |
| `npx` not found | Install Node.js or set the full path to `npx` in the Command field |
