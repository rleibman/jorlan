/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.connector

import jorlan.*
import zio.*
import zio.json.ast.Json

/** Optional mixin for skills that want to contribute per-skill metrics to the dashboard.
  *
  * Implement `dashboardData` to return any JSON blob the skill's UI widget understands.
  */
trait HasDashboardData { self: Skill =>

  def dashboardData(ctx: InvocationContext): IO[JorlanError, Json]

}
