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

  /** Retrieves the JSON value for `key`, or `None` if absent. Never fails. */
  def get(key: String): UIO[Option[Json]]

  /** Upserts `value` under `key`. Never fails. */
  def set(
    key:   String,
    value: Json,
  ): UIO[Unit]

}

object ServerSettingsRepository {

  val InitializedKey = "initialized"
  val ServerNameKey = "serverName"

  def get(key: String): URIO[ServerSettingsRepository, Option[Json]] =
    ZIO.serviceWithZIO[ServerSettingsRepository](_.get(key))

  def set(
    key:   String,
    value: Json,
  ): URIO[ServerSettingsRepository, Unit] =
    ZIO.serviceWithZIO[ServerSettingsRepository](_.set(key, value))

  /** Reads the `initialized` flag from `server_settings`. Returns `false` if the key is absent or not a JSON boolean.
    */
  def isServerInitialized: URIO[ServerSettingsRepository, Boolean] =
    ZIO.serviceWithZIO[ServerSettingsRepository](_.get(InitializedKey)).map {
      case Some(Json.Bool(v)) => v
      case _                  => false
    }

}
