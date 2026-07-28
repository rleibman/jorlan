-- HTTP headers sent on every request to an MCP server reachable over the Http/HttpSse transports.
-- This is how a remote MCP server is authenticated (typically `Authorization: Bearer <token>`); before this
-- column there was no way to authenticate one at all. Ignored for the Stdio transport, which uses `env`.
ALTER TABLE `mcpServer`
  ADD COLUMN `headers` LONGTEXT NOT NULL DEFAULT '{}';  -- JSON object of string->string
