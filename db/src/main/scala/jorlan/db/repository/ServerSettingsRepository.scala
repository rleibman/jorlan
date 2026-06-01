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

import jorlan.domain.{Formality, Personality}
import zio.*
import zio.json.*
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

  /** Key whose value is a JSON boolean (`true`/`false`) indicating whether first-run initialization has completed. */
  val InitializedKey = "initialized"

  /** Key whose value is a JSON string holding the human-readable server name set during initialization. */
  val ServerNameKey = "serverName"

  /** Key whose value is a JSON object encoding the [[Personality]] for this server installation. */
  val PersonalityKey = "personality"

  /** Reads the `initialized` flag from `server_settings`. Returns `false` if the key is absent or not a JSON boolean.
    */
  def isServerInitialized: URIO[ServerSettingsRepository, Boolean] =
    ZIO.serviceWithZIO[ServerSettingsRepository](_.get(InitializedKey)).map {
      case Some(Json.Bool(v)) => v
      case _                  => false
    }

  /** Reads and decodes the server personality. Falls back to [[Personality.default]] if the key is absent or the JSON
    * cannot be decoded, logging a warning in the latter case.
    */
  def getPersonality: URIO[ServerSettingsRepository, Personality] =
    ZIO.serviceWithZIO[ServerSettingsRepository](_.get(PersonalityKey)).flatMap {
      case Some(json) =>
        json.as[Personality] match {
          case Right(p)  => ZIO.succeed(p)
          case Left(err) =>
            ZIO.logWarning(
              s"Corrupt personality in server_settings (key=$PersonalityKey): $err. Falling back to default.",
            ) *> ZIO.succeed(Personality.default)
        }
      case None => ZIO.succeed(Personality.default)
    }

  /** Encodes and persists the given personality. Never fails (encoding errors die). */
  def setPersonality(p: Personality): URIO[ServerSettingsRepository, Unit] =
    ZIO
      .fromEither(p.toJsonAST.left.map(msg => new RuntimeException(msg)))
      .orDie
      .flatMap(json => ZIO.serviceWithZIO[ServerSettingsRepository](_.set(PersonalityKey, json)))

}
