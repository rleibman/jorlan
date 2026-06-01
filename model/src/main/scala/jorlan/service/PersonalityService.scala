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
import jorlan.domain.Personality
import zio.*

//TODO we really don't need that many services! We can probably remove the personality service completely and just make it a method of ServerSettingsRepository.
/** Reads and writes the server personality stored under key `personality` in `server_settings`.
  *
  * The implementation caches the current value in a `Ref` and refreshes it on every `update` call, so in-process reads
  * never hit the database except on startup.
  */
trait PersonalityService {

  /** Returns the current server personality. Never fails — returns [[Personality.default]] if the setting is absent. */
  def get(): UIO[Personality]

  /** Persists the new personality, refreshes the in-memory cache, and returns the saved value. */
  def update(p: Personality): IO[JorlanError, Personality]

}

object PersonalityService {

  def get(): URIO[PersonalityService, Personality] =
    ZIO.serviceWithZIO[PersonalityService](_.get())

  def update(p: Personality): ZIO[PersonalityService, JorlanError, Personality] =
    ZIO.serviceWithZIO[PersonalityService](_.update(p))

}
