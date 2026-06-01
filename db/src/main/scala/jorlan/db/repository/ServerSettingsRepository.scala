/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.db.repository

import zio.*
import zio.json.ast.Json

/** Read/write access to the `server_settings` key-value table.
  *
  * All values are stored as JSON so settings can hold scalars, strings, or nested objects without schema changes. The
  * table is created by Flyway migration V017.
  */
trait ServerSettingsRepository {

  def get(key: String): UIO[Option[Json]]
  def set(
    key:   String,
    value: Json,
  ): UIO[Unit]

}

object ServerSettingsRepository {

  def get(key: String): URIO[ServerSettingsRepository, Option[Json]] =
    ZIO.serviceWithZIO[ServerSettingsRepository](_.get(key))

  def set(
    key:   String,
    value: Json,
  ): URIO[ServerSettingsRepository, Unit] =
    ZIO.serviceWithZIO[ServerSettingsRepository](_.set(key, value))

}

// Row type used by QuillServerSettingsRepository (defined in QuillRepositories.scala).
// Note: the DB column is `setting_key` (not `key`) because `key` is a reserved word in MariaDB.
case class ServerSettingRow(
  settingKey: String,
  value:      String,
)
