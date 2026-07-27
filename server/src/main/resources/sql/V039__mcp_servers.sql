-- Dedicated table for MCP server configurations, replacing the server_settings['mcp.servers'] JSON blob.
-- Existing rows are migrated out of server_settings at application startup (McpServerMigration), which then
-- deletes the old key; this migration only creates the table.
CREATE TABLE IF NOT EXISTS `mcpServer` (
  `name`      VARCHAR(128)  NOT NULL,
  `transport` VARCHAR(16)   NOT NULL,
  `command`   VARCHAR(1024) NULL,
  `args`      LONGTEXT      NOT NULL,   -- JSON array of strings
  `env`       LONGTEXT      NOT NULL,   -- JSON object of string->string
  `url`       VARCHAR(1024) NULL,
  `enabled`   BOOLEAN       NOT NULL DEFAULT TRUE,
  `keywords`  LONGTEXT      NOT NULL,   -- JSON array of strings
  `createdAt` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updatedAt` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
