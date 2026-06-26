/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.connector

import jorlan.JorlanError
import zio.*
import zio.http.Client
import zio.json.ast.Json

/** Factory interface for dynamically loaded skill plugins.
  *
  * Implementations are discovered at runtime via Java's [[java.util.ServiceLoader]] from JARs placed in the server's
  * configured plugins directory. Each JAR must contain a
  * `META-INF/services/jorlan.connector.SkillPlugin` file listing the implementing class.
  *
  * The server classloader is used as parent so all Jorlan/ZIO/zio-http types are shared — no dependency hell.
  */
trait SkillPlugin {

  /** Construct the [[Skill]] instance.
    *
    * @param client
    *   ZIO HTTP client for outbound HTTP calls.
    * @param getSetting
    *   Read a key from `server_settings`; returns `None` if the key is absent.
    * @param setSetting
    *   Write a key to `server_settings`.
    */
  def create(
    client:     Client,
    getSetting: String => UIO[Option[Json]],
    setSetting: (String, Json) => UIO[Unit],
  ): IO[JorlanError, Skill]

}
