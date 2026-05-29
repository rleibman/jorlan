/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.shell

import com.typesafe.config.ConfigFactory
import zio.*
import zio.config.magnolia.DeriveConfig
import zio.config.typesafe.TypesafeConfigProvider

import java.io.File
import scala.language.unsafeNulls

case class ShellConfig(
  serverUrl: String = "http://localhost:8080",
  email:     Option[String] = None,
  password:  Option[String] = None,
)

object ShellConfig {

  private val descriptor = DeriveConfig.derived[ShellConfig].desc.nested("jorlan", "shell")

  val layer: TaskLayer[ShellConfig] = ZLayer.fromZIO {
    ZIO
      .attempt {
        val homeDir = java.lang.System.getenv("HOME")
        val userFile = Option(homeDir)
          .map(h => new File(s"$h/.jorlan/jorlan.json"))
          .filter(_.exists())

        val base = ConfigFactory.load()
        userFile
          .map(f => ConfigFactory.parseFile(f).withFallback(base).resolve())
          .getOrElse(base)
      }.flatMap { typesafeConfig =>
        TypesafeConfigProvider
          .fromTypesafeConfig(typesafeConfig)
          .load(descriptor)
          .mapError(e => new RuntimeException(e.getMessage))
      }
  }

  /** Override config with values provided by command-line args.
    *
    * Supported flags: `--server-url`, `--email`, `--password`
    */
  def applyArgs(
    cfg:  ShellConfig,
    args: List[String],
  ): ShellConfig = {
    def parseArgs(
      remaining: List[String],
      acc:       ShellConfig,
    ): ShellConfig =
      remaining match {
        case "--server-url" :: url :: rest => parseArgs(rest, acc.copy(serverUrl = url))
        case "--email" :: email :: rest    => parseArgs(rest, acc.copy(email = Some(email)))
        case "--password" :: pass :: rest  => parseArgs(rest, acc.copy(password = Some(pass)))
        case _ :: rest                     => parseArgs(rest, acc)
        case Nil                           => acc
      }
    parseArgs(args, cfg)
  }

}
