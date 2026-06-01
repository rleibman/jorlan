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

/** Test double for [[PersonalityService]]. Returns [[Personality.default]] on every `get()` and echoes inputs on
  * `update()`. Follows the same pattern as [[FakeModelGateway]].
  */
object FakePersonalityService {

  val layer: ULayer[PersonalityService] =
    ZLayer.succeed(new PersonalityService {
      override def get():                  UIO[Personality] = ZIO.succeed(Personality.default)
      override def update(p: Personality): IO[JorlanError, Personality] = ZIO.succeed(p)
    })

}
