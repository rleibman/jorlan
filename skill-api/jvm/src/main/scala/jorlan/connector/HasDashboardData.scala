/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
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
