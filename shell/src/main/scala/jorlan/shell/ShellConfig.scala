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
import zio.json.*

import java.io.File
import scala.annotation.tailrec
import scala.language.unsafeNulls

case class ShellConfig(
  serverUrl: String = "http://localhost:8080",
  email:     Option[String] = None,
  password:  Option[String] = None,
) derives JsonEncoder

object ShellConfig {

  private val descriptor = DeriveConfig.derived[ShellConfig].desc.nested("jorlan", "shell")

  /** Config file load order:
    *   1. `JORLAN_SHELL_CONFIG` env var (absolute path)
    *   2. `~/.jorlan/jorlan-shell.json`
    *   3. `~/.jorlan/jorlan.json` (backwards compatibility — read only, never written)
    *   4. `application.conf` defaults
    */
  val layer: TaskLayer[ShellConfig] = ZLayer.fromZIO {
    for {
      readFile       <- findReadFile(Nil)
      typesafeConfig <- ZIO.attempt {
        val base = ConfigFactory.load()
        readFile
          .map(f => ConfigFactory.parseFile(f).withFallback(base).resolve())
          .getOrElse(base)
      }
      cfg <- TypesafeConfigProvider
        .fromTypesafeConfig(typesafeConfig)
        .load(descriptor)
        .mapError(e => new RuntimeException(e.getMessage))
    } yield cfg
  }

  /** Determine the file to read config from, given a set of CLI args.
    *
    * Priority: env var → `--config` arg → `jorlan-shell.json` → `jorlan.json`.
    */
  private[shell] def findReadFile(args: List[String]): UIO[Option[File]] =
    ZIO.succeed {
      val homeDir = java.lang.System.getProperty("user.home", "")
      val envFile = Option(java.lang.System.getenv("JORLAN_SHELL_CONFIG")).map(new File(_)).filter(_.exists())
      val argFile = args.sliding(2).collectFirst { case List("--config", path) => new File(path) }.filter(_.exists())
      val newFile = new File(s"$homeDir/.jorlan/jorlan-shell.json")
      val legacyFile = new File(s"$homeDir/.jorlan/jorlan.json")

      envFile
        .orElse(argFile)
        .orElse(Option(newFile).filter(_.exists()))
        .orElse(Option(legacyFile).filter(_.exists()))
    }

  /** Determine the file path to write the config to. Never uses the legacy file. */
  def resolveWritePath(args: List[String]): UIO[File] =
    ZIO.succeed {
      val homeDir = java.lang.System.getProperty("user.home", "")
      val envFile = Option(java.lang.System.getenv("JORLAN_SHELL_CONFIG")).map(new File(_))
      val argFile = args.sliding(2).collectFirst { case List("--config", path) => new File(path) }
      envFile.orElse(argFile).getOrElse(new File(s"$homeDir/.jorlan/jorlan-shell.json"))
    }

  /** `true` when no config file exists or the loaded config has no serverUrl. */
  def isFirstRun(
    cfg:  ShellConfig,
    args: List[String],
  ): UIO[Boolean] =
    findReadFile(args).map { fileOpt =>
      fileOpt.isEmpty || cfg.serverUrl.isEmpty
    }

  /** Write `cfg` to `path` in the canonical JSON format that `layer` can load back. */
  def write(
    path: File,
    cfg:  ShellConfig,
  ): Task[Unit] =
    ZIO.attempt {
      val json = ShellConfigFile(ShellConfigRoot(cfg)).toJson
      val parent = path.getParentFile
      if (parent != null && !parent.exists()) parent.mkdirs(): @annotation.nowarn("msg=discarded non-Unit value")
      java.nio.file.Files.writeString(path.toPath, json)
    }.unit

  /** Override config with values provided by command-line args.
    *
    * Supported flags: `--server-url`, `--email`, `--password`
    */
  def applyArgs(
    cfg:  ShellConfig,
    args: List[String],
  ): ShellConfig = {
    @tailrec
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

private case class ShellConfigFile(jorlan: ShellConfigRoot) derives JsonEncoder
private case class ShellConfigRoot(shell: ShellConfig) derives JsonEncoder
