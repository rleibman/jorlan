<!--
 Copyright (c) 2026 Roberto Leibman - All Rights Reserved
-->

# Phase 14.3: MCP Compatibility Adapter

**Branch:** `phase-14.3/mcp-adapter` (planned)  
**Status:** Design — 2026-06-16  
**Date:** 2026-06-16

---

## 1. Problem Statement

Jorlan agents can currently invoke built-in skills (memory, scheduler, email, etc.) and connector
skills (Telegram). A large ecosystem of third-party **MCP (Model Context Protocol)** servers exists
— filesystem, git, databases, REST APIs, browser automation — each exposing typed tools through a
standard JSON-RPC 2.0 protocol. Phase 14.3 makes Jorlan a first-class MCP client: any configured
MCP server's tools appear automatically in the skill registry as Tier-4 skills that agents can call.

---

## 2. Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| SBT module | `server` (no new module) | Roadmap says "basic functionality, server module" |
| Transport | stdio + HTTP (POST-based) | Covers local subprocesses and remote MCP servers |
| Protocol impl | Minimal JSON-RPC in server | Keeps LangChain4j strictly in `ai`; protocol is ~3 methods |
| Config location | `server_settings` key `"mcp.servers"` | Same pattern as market/lyrion; no new migration |
| Tool namespace | `mcp.{serverName}.{toolName}` | Prevents name collisions across multiple MCP servers |
| Skill tier | Tier 4 (external/untrusted) | Per roadmap; tools come from unknown third parties |
| Required capability | `mcp.call` | Distinct from `agent.skill.invoke`; finer-grained control |
| Lifecycle | Stdio: subprocess per server; HTTP: stateless | Subprocesses started at registration time |
| Missing/broken server | Log warning, skip | Same pattern as other optional skills |

---

## 3. MCP Protocol (minimal subset)

We implement only the three methods needed for tool import and invocation.

### 3.1 Initialize handshake (required by spec)

```json
// Client → Server
{"jsonrpc":"2.0","id":1,"method":"initialize",
 "params":{"protocolVersion":"2024-11-05","capabilities":{},
            "clientInfo":{"name":"jorlan","version":"1.0"}}}

// Server → Client
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}}}}

// Client → Server (notification, no id)
{"jsonrpc":"2.0","method":"notifications/initialized"}
```

### 3.2 List tools

```json
// Client → Server
{"jsonrpc":"2.0","id":2,"method":"tools/list"}

// Server → Client
{"jsonrpc":"2.0","id":2,"result":{"tools":[
  {"name":"read_file","description":"Read a file","inputSchema":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}
]}}
```

### 3.3 Call tool

```json
// Client → Server
{"jsonrpc":"2.0","id":3,"method":"tools/call",
 "params":{"name":"read_file","arguments":{"path":"/tmp/hello.txt"}}}

// Server → Client (success)
{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"Hello!"}]}}

// Server → Client (tool error — still a successful JSON-RPC response)
{"jsonrpc":"2.0","id":3,"result":{"isError":true,"content":[{"type":"text","text":"File not found"}]}}
```

---

## 4. New Types

### 4.1 Domain types (in `model`)

No new domain types needed. Config lives in `server_settings` as `Json`.

### 4.2 MCP config types (in `server`)

```scala
// server/src/main/scala/jorlan/service/mcp/McpConfig.scala

enum McpTransport derives JsonEncoder, JsonDecoder:
  case Stdio, Http

case class McpServerConfig(
  name:      String,
  transport: McpTransport,
  // stdio only
  command:   Option[String] = None,
  args:      List[String]   = List.empty,
  env:       Map[String, String] = Map.empty,
  // http only
  url:       Option[String] = None,
  enabled:   Boolean        = true,
)
object McpServerConfig:
  given JsonDecoder[McpServerConfig] = DeriveJsonDecoder.gen[McpServerConfig]
  given JsonEncoder[McpServerConfig] = DeriveJsonEncoder.gen[McpServerConfig]
```

### 4.3 JSON-RPC / MCP wire types (in `server`, private to MCP package)

```scala
// Internal only — not exposed as domain types

case class JsonRpcRequest(
  jsonrpc: String = "2.0",
  id:      Option[Int],
  method:  String,
  params:  Option[Json],
)

case class JsonRpcResponse(
  id:     Option[Int],
  result: Option[Json],
  error:  Option[Json],
)

case class McpTool(
  name:        String,
  description: Option[String],
  inputSchema: Json,
)

case class McpToolResult(
  content: List[McpContent],
  isError: Option[Boolean] = None,
)

case class McpContent(
  `type`: String,   // "text" | "image" | "resource"
  text:   Option[String],
)
```

---

## 5. `McpClient` Trait and Implementations

```scala
// server/src/main/scala/jorlan/service/mcp/McpClient.scala

trait McpClient {
  def listTools: IO[JorlanError, List[McpTool]]
  def callTool(name: String, arguments: Json): IO[JorlanError, String]
}
```

### 5.1 `StdioMcpClient`

Uses `zio-process` to fork a subprocess. Communicates over stdin/stdout using newline-delimited
JSON. A `Ref[Int]` tracks the request ID counter. A `Hub` or `Queue` pair serializes concurrent
requests (MCP over stdio is request-response; we must not interleave).

**Key implementation notes:**
- Use `zio.process.Command` to spawn the subprocess.
- The subprocess stdin/stdout streams are `ZStream[Any, IOException, Byte]`.
- Serialize concurrent calls with a `Semaphore(1)` (one in-flight request at a time on stdio).
- Send `initialize` + `notifications/initialized` during `ZIO.acquireRelease` setup.
- If the subprocess exits unexpectedly, surface as `JorlanError`.

### 5.2 `HttpMcpClient`

Uses `zio-http` `Client` to POST JSON-RPC to the configured URL. Each call is independent (HTTP is
stateless). The `initialize` handshake is sent once lazily (cached result in `Ref`).

```scala
// POST url with Content-Type: application/json
// Body: JsonRpcRequest serialized
// Response: JsonRpcResponse
```

### 5.3 Shared logic

Both implementations share a `McpClientOps` mixin:
- `sendRequest(method, params)` — common JSON-RPC dispatch
- `parseToolsListResponse(json)` → `List[McpTool]`
- `parseCallToolResponse(json)` → `String` (concatenates all `text` content items)

---

## 6. `McpSkillAdapter` — implements `Skill`

One `McpSkillAdapter` instance per configured MCP server. It is constructed with an `McpClient`
and the discovered `List[McpTool]`.

```scala
// server/src/main/scala/jorlan/service/mcp/McpSkillAdapter.scala

class McpSkillAdapter(
  serverName: String,
  tools:      List[McpTool],
  client:     McpClient,
) extends Skill {

  override val descriptor: SkillDescriptor = SkillDescriptor(
    name        = s"mcp.$serverName",
    description = s"MCP server: $serverName",
    tier        = SkillTier.T4,
    tools       = tools.map { t =>
      ToolDescriptor(
        name            = s"mcp.$serverName.${t.name}",
        description     = t.description.getOrElse(""),
        inputSchema     = t.inputSchema,
        requiredCapability = "mcp.call",
        riskClass       = RiskClass.ExternalEffect,
      )
    },
  )

  override def invoke(
    toolName: String,
    argsJson: Json,
    context:  InvocationContext,
  ): IO[JorlanError, Json] = {
    val mcpToolName = toolName.stripPrefix(s"mcp.$serverName.")
    client.callTool(mcpToolName, argsJson).map(Json.Str(_))
  }
}
```

---

## 7. `McpManager` — lifecycle + registration

```scala
// server/src/main/scala/jorlan/service/mcp/McpManager.scala

trait McpManager {
  def loadAndRegister: IO[JorlanError, Unit]
}
```

`McpManagerImpl` in `server`:

1. Calls `registry.unregisterWhere(_.startsWith("mcp."))` to purge any previously registered MCP skills.
   This ensures servers removed or disabled in config don't remain as stale skills after a reload.
2. Reads `server_settings` key `"mcp.servers"` via `ServerSettingsRepository.get`.
3. If absent or empty array, logs debug and returns.
4. For each enabled `McpServerConfig`:
   a. Creates the appropriate `McpClient` (`StdioMcpClient` or `HttpMcpClient`).
   b. Calls `client.listTools` to discover tools.
   c. Constructs `McpSkillAdapter(serverName, tools, client)`.
   d. Calls `SkillRegistry.register(adapter)`.
   e. On any error: logs warning with server name + error message; continues with remaining servers.

### 7.1 Namespace format and SkillRegistry routing

Skill name: `mcp.<sanitizedServerName>` (e.g., `mcp.filesystem`, `mcp.my.server.com`).  
Tool names: `mcp.<sanitizedServerName>.<mcpToolName>`.

`serverName` sanitization: characters matching `[^A-Za-z0-9_.]` are replaced with `_`; dots are **preserved**
so naturally dotted server names (e.g., hostnames) remain readable.

`SkillRegistry.invoke` uses **longest-prefix matching**: it finds the registered skill whose name, followed by
`"."`, is the longest prefix of the tool name. This correctly handles dotted skill names without confusion between
`mcp.my` and `mcp.my.server.com`.

**Wiring in `Jorlan.scala`:**

```scala
_ <- ZIO.serviceWithZIO[McpManager](_.loadAndRegister)
```

Called after `SkillRegistry` is initialized, before `TriggerEngine` starts.

---

## 8. GraphQL and Shell Surface

### 8.1 GraphQL additions

No new GQL types needed. The `skills` query already returns all registered skills including MCP
adapters, so the LLM and admin can see them.

One new mutation for hot-reloading MCP servers without restart:

```graphql
# Reload MCP server configs from server_settings and re-register
reloadMcpServers: Boolean!
```

Capability gate: `admin.mcp.reload` (seeded for admin at init).

### 8.2 Shell commands

```
/mcp list           — show configured MCP servers and their tool counts
/mcp reload         — reload MCP servers from server_settings (calls reloadMcpServers mutation)
```

---

## 9. Capability Seeding

Add to `InitService.complete` system capabilities:

```scala
"mcp.call"         -> ApprovalMode.PerInvocation   // external tool calls need per-invocation approval
"admin.mcp.reload" -> ApprovalMode.Persistent       // admin can always reload
```

---

## 10. Tests

### 10.1 Unit tests (server module)

**`McpClientSpec`**: Uses an embedded HTTP server (`zio-http` `Server.install`) as a fake MCP
server returning canned JSON-RPC responses. Tests:
- `initialize` is called on first `listTools`
- `listTools` maps response to `List[McpTool]`
- `callTool` returns concatenated text content
- `callTool` with `isError: true` fails with `JorlanError`
- JSON-RPC error response (non-null `error` field) fails with `JorlanError`

**`StdioMcpClientSpec`**: Uses a minimal Scala subprocess (echo-based fake) to test:
- Subprocess spawned and receives JSON-RPC messages
- Response deserialized correctly
- Subprocess failure propagated as `JorlanError`

**`McpSkillAdapterSpec`**: Uses a fake `McpClient`. Tests:
- `descriptor` tool list matches MCP tool names with `mcp.{server}.` prefix
- `invoke` strips prefix and delegates to client
- `invoke` with unknown tool name → `ValidationError`

**`McpManagerSpec`**: Uses `InMemoryRepositories` + fake `SkillRegistry`. Tests:
- No `mcp.servers` key → no skills registered, no error
- `mcp.servers` empty array → no skills registered
- One disabled server → skipped
- One HTTP server config → registers adapter
- Broken server (tool list fails) → warning logged, other servers still registered

### 10.2 Integration test

**`McpIntegrationSpec`** (server module, no Testcontainers needed):
- Starts embedded `zio-http` fake MCP server with 2 tools
- Sets `server_settings` key `"mcp.servers"` via `InMemoryRepositories`
- Calls `McpManager.loadAndRegister`
- Asserts both tools visible in `SkillRegistry.allTools`
- Calls `SkillRegistry.invoke` on one tool; asserts result

---

## 11. Open Questions (resolved)

| Question | Decision |
|----------|----------|
| SSE vs POST transport for HTTP | POST-based only (simpler; all modern MCP servers support it) |
| Concurrent stdio calls | Serialize with `Semaphore(1)` — stdio is inherently sequential |
| Tool name collision (two servers same tool name) | Namespaced: `mcp.{serverName}.{toolName}` — callers must use full name |
| Hot reload | Via `reloadMcpServers` mutation — unregisters old adapters, re-reads config, re-registers |
| Stdio subprocess cleanup on server shutdown | `ZIO.addFinalizer` on client construction to kill subprocess |
| MCP version negotiation | Send `2024-11-05` as `protocolVersion`; accept any response (no strict version check) |
| Input schema passthrough | Store as `Json` in `ToolDescriptor.inputSchema`; no schema transformation |

---

## 12. Migration Plan

No new Flyway migration needed. Config stored in existing `server_settings` table under key
`"mcp.servers"`.

Next migration after Phase 14.3 remains V026 (or whatever the next sequential number is).
