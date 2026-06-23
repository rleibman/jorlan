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
import caliban.ScalaJSClientAdapter
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.web.util.ApiClientSttp4
import net.leibman.jorlan.muiMaterial.components.{Alert, CssBaseline, ThemeProvider}
import net.leibman.jorlan.muiMaterial.stylesCreateThemeMod.ThemeOptions
import net.leibman.jorlan.muiMaterial.stylesMod
import org.scalajs.dom
import sttp.model.Uri

import scala.language.unsafeNulls
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExport, JSImport}

@js.native
@JSImport("react", JSImport.Namespace)
private object ReactModule extends js.Object

object JorlanWebApp {

  /** Parsed URI for the Jorlan GraphQL API endpoint. Computed once at startup from `window.location`. */
  val serverUri: Uri = {
    val protocol = if (org.scalajs.dom.window.location.protocol == "https:") "https" else "http"
    val host = org.scalajs.dom.window.location.host
    Uri
      .parse(s"$protocol://$host/api/jorlan").fold(
        err => throw new IllegalStateException(s"Cannot parse server URI: $err"),
        identity,
      )
  }

  /** Creates a new GraphQL client adapter for the current page load. */
  def makeAdapter(): ScalaJSClientAdapter = ScalaJSClientAdapter(serverUri)

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

  /** Read the `oauth` query parameter from the current URL (set by the OAuth callback redirect). */
  private def oauthResultFromUrl(): Option[String] = {
    val search = dom.window.location.search
    if (search.contains("oauth=success")) Some("success")
    else if (search.contains("oauth=error")) Some("error")
    else None
  }

  val component =
    ScalaFnComponent
      .withHooks[Unit]
      .useState(Option.empty[Option[User]])
      .useState(oauthResultFromUrl()) // oauth toast: Some("success") | Some("error") | None
      .useEffectOnMountBy {
        (
          _,
          userState,
          _,
        ) =>
          AuthClient
            .whoami[User, ConnectionId](Some(ApiClientSttp4.connectionId))
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
          oauthToast,
        ) =>
          ThemeProvider(theme)(
            CssBaseline(),
            // OAuth callback result banner — shown when redirected back from Google with ?oauth=success/error
            oauthToast.value.fold(EmptyVdom) { result =>
              Alert.severity(if (result == "success") "success" else "error")(
                if (result == "success") "Google account connected successfully."
                else "Failed to connect Google account. Please try again.",
              )
            },
            userState.value match {
              case None =>
                <.div(^.className := "loading")("Loading…")
              case Some(None) =>
                LoginRouter[ConnectionId](
                  connectionId = Some(ApiClientSttp4.connectionId),
                  oauthProviders = List.empty,
                )
              case Some(Some(user)) =>
                AppRouter(user)
            },
          )
      }

  @JSExport
  def main(args: Array[String]): Unit = {
    // Expose bundled React as window.React so skill scripts (NoModule) can reference it as a global.
    dom.window.asInstanceOf[js.Dynamic].React = ReactModule.asInstanceOf[js.Dynamic]
    val container = dom.document.getElementById("content")
    val root = ReactDOMClient.createRoot(container)
    root.render(component())
    ()
  }

}
