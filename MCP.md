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

## OurGroceries MCP Server

[OurGroceries](https://www.ourgroceries.com/) is a shared grocery-list app.
This MCP server lets Jorlan agents read and manage your grocery lists.

**Package**: [`@sergib/ourgroceries-mcp`](https://github.com/sargue/ourgroceries-mcp) (v2.1.0+)

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

---

## Prerequisites

- Node.js 18+ and `npx` available on the machine running Jorlan
- An [OurGroceries account](https://www.ourgroceries.com/)

---

## Step 1 — Authenticate (one-time)

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

---

## Step 2 — Register the Server in Jorlan

### Via the Admin UI

1. Navigate to **Settings → MCP Servers**.
2. Click **Add Server**.
3. Fill in the form:

   | Field     | Value |
   |-----------|-------|
   | Name      | `ourgroceries` |
   | Transport | `Stdio` |
   | Command   | `npx` |
   | Args      | `-y, @sergib/ourgroceries-mcp` |
   | Env vars  | *(leave empty — credentials come from the config file)* |
   | Enabled   | ✓ |

4. Click **Save**.

### Alternative: env-var credentials

If you prefer to pass credentials explicitly instead of relying on the config file,
obtain the values from `~/.config/ourgroceries-mcp/config.json` after login
and add them as env vars in the form:

| Key | Value |
|-----|-------|
| `OURGROCERIES_AUTH_COOKIE` | *(value from config file)* |
| `OURGROCERIES_TEAM_ID` | *(value from config file)* |

---

## Step 3 — Reload MCP Servers

After saving, click **Reload** on the MCP Servers page (or use the GraphQL mutation
`reloadMcpServers` — requires the `admin.mcp.reload` capability).

Jorlan will spawn the subprocess, fetch the tool catalogue, and register the tools.

---

## Step 4 — Verify

The OurGroceries tools should now appear in **Settings → Skill Registry** under the
`ourgroceries` namespace. If you don't see them, check the server logs for subprocess
startup errors.

---

## Using OurGroceries in an Agent Session

Once registered, agents can use natural-language instructions:

- *"Add milk and eggs to the Costco list."*
- *"What's on the weekly shopping list?"*
- *"Cross off everything on the Trader Joe's list."*

No extra capability grants are required — MCP tool access follows the agent's existing
permission model.

---

## Troubleshooting

| Symptom | Check |
|---------|-------|
| Tools don't appear after reload | Check Jorlan server logs for subprocess errors; verify `npx` is on `$PATH` for the jorlan user |
| Authentication errors | Re-run `npx -y @sergib/ourgroceries-mcp login` as the correct OS user |
| `config.json` not found | Jorlan is running as a different user than the one that logged in; use env-var credentials instead |
| `npx` not found | Install Node.js or set the full path to `npx` in the Command field (e.g. `/usr/local/bin/npx`) |
| Subprocess exits immediately | Run `npx -y @sergib/ourgroceries-mcp` manually in a terminal to see the error output |

---

## Adding Other MCP Servers

The same pattern applies to any MCP server distributed via npm:

1. Find the package name and run command from the server's README.
2. Run any one-time authentication the server requires (as the jorlan OS user).
3. Register in **Settings → MCP Servers** with transport `Stdio`, command `npx`,
   and the package as the first arg.
4. Reload.

For HTTP-transport MCP servers, set transport to `Http` and provide the server URL
instead of a command.
