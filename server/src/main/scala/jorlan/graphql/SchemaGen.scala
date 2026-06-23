/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.graphql

/** Standalone schema renderer.
  *
  * Run via `sbt "server/runMain jorlan.graphql.SchemaGen"` and redirect stdout to capture the SDL schema file.
  *
  * @see
  *   `scripts/gen-schema.sh`
  */
object SchemaGen {

  def main(args: Array[String]): Unit = println(JorlanAPI.api.render)

}
