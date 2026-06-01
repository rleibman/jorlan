/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.service

import jorlan.*
import jorlan.db.repository.ServerSettingsRepository
import jorlan.domain.Personality
import zio.*
import zio.json.*

// TODO use zio-cache instead of a Ref.
/** [[PersonalityService]] implementation backed by [[ServerSettingsRepository]].
  *
  * On layer construction the current personality is loaded from `server_settings` (key `personality`), decoded, and
  * cached in a [[Ref]]. If the stored JSON is absent, `Personality.default` is used. If it is present but fails to
  * decode (e.g. after a manual DB edit or schema drift), a warning is logged and `Personality.default` is used as
  * fallback. The [[Ref]] is updated atomically on every [[update]] call.
  */
class PersonalityServiceImpl(
  settings:  ServerSettingsRepository,
  cachedRef: Ref[Personality],
) extends PersonalityService {

  override def get(): UIO[Personality] = cachedRef.get

  override def update(p: Personality): IO[JorlanError, Personality] =
    for {
      json <- ZIO
        .fromEither(p.toJsonAST)
        .mapError(msg => JorlanError(s"Failed to encode personality: $msg"))
      _ <- settings.set(ServerSettingsRepository.PersonalityKey, json)
      _ <- cachedRef.set(p)
    } yield p

}

object PersonalityServiceImpl {

  val live: URLayer[ServerSettingsRepository, PersonalityService] =
    ZLayer.fromZIO(
      for {
        settings <- ZIO.service[ServerSettingsRepository]
        loaded   <- settings.get(ServerSettingsRepository.PersonalityKey).flatMap {
          case Some(json) =>
            json.as[Personality] match {
              case Right(p)  => ZIO.succeed(p)
              case Left(err) =>
                ZIO.logWarning(
                  s"Corrupt personality in server_settings (key=${ServerSettingsRepository.PersonalityKey}): $err. Falling back to default.",
                ) *> ZIO.succeed(Personality.default)
            }
          case None => ZIO.succeed(Personality.default)
        }
        cached <- Ref.make(loaded)
      } yield new PersonalityServiceImpl(settings, cached),
    )

}
