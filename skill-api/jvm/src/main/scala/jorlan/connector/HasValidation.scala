/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.connector

import jorlan.*
import zio.*

/** Optional mixin for skills that can validate their configuration (API keys, locations, connectivity, etc.).
  *
  * Called by the admin UI after the user saves a new config so problems are surfaced immediately.
  */
trait HasValidation { self: Skill =>

  def validate(): IO[JorlanError, SkillValidationResult]

}
