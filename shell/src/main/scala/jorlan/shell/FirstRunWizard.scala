/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell

import jorlan.shell.client.InitClient
import jorlan.shell.tui.{JorlanScreen, MessageKind}
import zio.*

import java.io.File
import scala.language.unsafeNulls

/** Drives the first-run setup wizard when no config file exists or `serverUrl` is absent.
  *
  * Returns a [[ShellConfig]] with the resolved `serverUrl`, `email`, and `password` to use for the first login. Writes
  * the config to `writePath` on success.
  */
object FirstRunWizard {

  def run(
    defaultServerUrl: ServerUrl,
    writePath:        File,
  ): ZIO[JorlanScreen & InitClient, Throwable, ShellConfig] =
    for {
      screen <- ZIO.service[JorlanScreen]
      _      <- screen.addMessage(MessageKind.System, "Jorlan Shell — First Run Setup")
      _      <- screen.addMessage(
        MessageKind.System,
        "No configuration found. Let's set up your connection.",
      )
      cfg <- setupLoop(defaultServerUrl, writePath, screen)
    } yield cfg

  // ─── Internal helpers ────────────────────────────────────────────────────────

  private def prompt(
    screen: JorlanScreen,
    label:  String,
  ): UIO[String] =
    screen.setInputPrompt(label) *> screen.readLine

  private def promptNonEmpty(
    screen: JorlanScreen,
    label:  String,
  ): UIO[String] = {
    lazy val loop: UIO[String] = prompt(screen, label).flatMap { v =>
      if (v.trim.isEmpty) {
        screen.addMessage(MessageKind.Error, "Value must not be empty.") *> loop
      } else ZIO.succeed(v.trim)
    }
    loop
  }

  private def setupLoop(
    defaultServerUrl: ServerUrl,
    writePath:        File,
    screen:           JorlanScreen,
  ): ZIO[InitClient, Throwable, ShellConfig] = {
    for {
      _      <- screen.setInputPrompt(s"Server URL [${defaultServerUrl.value}]: ")
      urlRaw <- screen.readLine
      serverUrl = if (urlRaw.trim.isEmpty) defaultServerUrl else ServerUrl(urlRaw.trim)
      cfg <- statusLoop(serverUrl, writePath, screen)
    } yield cfg
  }

  private def statusLoop(
    serverUrl: ServerUrl,
    writePath: File,
    screen:    JorlanScreen,
  ): ZIO[InitClient, Throwable, ShellConfig] = {
    InitClient
      .checkStatus(serverUrl).foldZIO(
        err => {
          screen.addMessage(MessageKind.Error, s"Cannot reach ${serverUrl.value}: $err") *>
            screen.addMessage(MessageKind.System, "Check the server URL and try again. Press Enter to retry.") *>
            screen.readLine *>
            setupLoop(serverUrl, writePath, screen)
        },
        status => {
          if (status.initialized) {
            screen.addMessage(
              MessageKind.System,
              s"Connected to ${status.serverName} (v${status.version}). Server is already set up.",
            ) *> loginPrompt(serverUrl, writePath, screen)
          } else {
            screen.addMessage(
              MessageKind.System,
              "Server needs setup. Find the JORLAN SETUP TOKEN in the server's console output, then enter it here.",
            ) *> initLoop(serverUrl, status.serverName, writePath, screen)
          }
        },
      )
  }

  private def loginPrompt(
    serverUrl: ServerUrl,
    writePath: File,
    screen:    JorlanScreen,
  ): ZIO[Any, Throwable, ShellConfig] = {
    for {
      email    <- promptNonEmpty(screen, "Email: ")
      password <- promptNonEmpty(screen, "Password: ")
      cfg = ShellConfig(serverUrl.value, Some(email), Some(password))
      _ <- ShellConfig.write(writePath, cfg)
      _ <- screen.addMessage(MessageKind.System, s"Config saved to ${writePath.getPath}")
    } yield cfg
  }

  private def isLocalhost(serverUrl: ServerUrl): Boolean =
    scala.util.Try(serverUrl.toUri.getHost).toOption.exists { h =>
      h == "localhost" || h == "127.0.0.1" || h == "::1"
    }

  private def initLoop(
    serverUrl:         ServerUrl,
    defaultServerName: String,
    writePath:         File,
    screen:            JorlanScreen,
  ): ZIO[InitClient, Throwable, ShellConfig] = {
    val skipToken = isLocalhost(serverUrl)
    for {
      token <-
        if (skipToken) {
          screen.addMessage(
            MessageKind.System,
            "Localhost detected — token not required.",
          ) *> ZIO.succeed("")
        } else {
          screen.setInputPrompt("Setup token: ") *> screen.readLine
        }
      _       <- screen.setInputPrompt(s"Server name [$defaultServerName]: ")
      nameRaw <- screen.readLine
      serverName = if (nameRaw.trim.isEmpty) defaultServerName else nameRaw.trim
      adminName  <- promptNonEmpty(screen, "Admin display name: ")
      adminEmail <- promptNonEmpty(screen, "Admin email: ")
      password   <- promptNonEmpty(screen, "Admin password: ")
      confirm    <- promptNonEmpty(screen, "Confirm password: ")
      cfg        <-
        if (password != confirm) {
          screen.addMessage(MessageKind.Error, "Passwords do not match. Please try again.") *>
            initLoop(serverUrl, defaultServerName, writePath, screen)
        } else {
          InitClient
            .complete(serverUrl, token.trim, serverName, adminEmail, adminName, password)
            .foldZIO(
              err =>
                screen.addMessage(MessageKind.Error, s"Setup failed: $err. Try entering the token again.") *>
                  initLoop(serverUrl, defaultServerName, writePath, screen),
              _ => {
                val cfg = ShellConfig(serverUrl.value, Some(adminEmail), Some(password))
                (ShellConfig.write(writePath, cfg) *>
                  screen.addMessage(MessageKind.System, s"Server initialized. Config saved to ${writePath.getPath}.") *>
                  screen.addMessage(MessageKind.System, "Waiting for server to switch to full mode…") *>
                  ZIO.sleep(5.seconds))
                  .as(cfg)
              },
            )
        }
    } yield cfg
  }

}
