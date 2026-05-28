/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
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
