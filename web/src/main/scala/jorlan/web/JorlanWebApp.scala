/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web

import auth.{AuthClient, LoginRouter}
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.domain.{ConnectionId, User, UserId}
import net.leibman.jorlan.muiMaterial.components.{CssBaseline, ThemeProvider}
import net.leibman.jorlan.muiMaterial.stylesMod
import net.leibman.jorlan.muiMaterial.stylesCreateThemeMod.ThemeOptions
import org.scalajs.dom

import scala.language.unsafeNulls
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExport

object JorlanWebApp {

  val connectionId: ConnectionId = ConnectionId.unsafeRandom

  private val theme = stylesMod.createTheme(
    js.Dynamic
      .literal(
        palette = js.Dynamic.literal(
          primary = js.Dynamic.literal(
            main = "#2563eb",
          ),
          secondary = js.Dynamic.literal(
            main = "#7c3aed",
          ),
          mode = "light",
        ),
        typography = js.Dynamic.literal(
          fontFamily = "'Inter', 'Roboto', 'Helvetica', 'Arial', sans-serif",
        ),
      )
      .asInstanceOf[ThemeOptions],
  )

  val component =
    ScalaFnComponent
      .withHooks[Unit]
      .useState(Option.empty[Option[User]])
      .useEffectOnMountBy {
        (
          _,
          userState,
        ) =>
          AuthClient
            .whoami[User, ConnectionId](Some(connectionId))
            .flatTap {
              case Some(user) => Callback.log(s"Authenticated: ${user.email}").asAsyncCallback
              case None       => Callback.log("Not authenticated").asAsyncCallback
            }
            .completeWith {
              case scala.util.Success(optUser) => userState.setState(Some(optUser))
              case scala.util.Failure(_)       => userState.setState(Some(None))
            }
      }
      .render {
        (
          _,
          userState,
        ) =>
          ThemeProvider(theme)(
            CssBaseline(),
            userState.value match {
              case None =>
                <.div(^.className := "loading")("Loading…")
              case Some(None) =>
                LoginRouter[ConnectionId](
                  connectionId = Some(connectionId),
                  oauthProviders = Nil,
                )
              case Some(Some(user)) =>
                AppRouter(user)
            },
          )
      }

  @JSExport
  def main(args: Array[String]): Unit = {
    val container = dom.document.getElementById("content")
    val root = ReactDOMClient.createRoot(container)
    root.render(component())
    ()
  }

}
