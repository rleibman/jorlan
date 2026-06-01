-- Server-level key/value configuration store.
-- All values are valid JSON (scalars, strings, objects) so settings can evolve
-- without schema migrations. The initialized flag and serverName are seeded here;
-- Phase 8.2 adds the personality key.
-- Note: 'key' is a reserved word in MariaDB; the column is named 'setting_key'.
CREATE TABLE `server_settings` (
  `setting_key` VARCHAR(64) NOT NULL,
  `value`       JSON        NOT NULL,
  PRIMARY KEY (`setting_key`)
);

INSERT INTO `server_settings` (`setting_key`, `value`) VALUES ('initialized', 'false');
INSERT INTO `server_settings` (`setting_key`, `value`) VALUES ('serverName',  '"Jorlan"');
