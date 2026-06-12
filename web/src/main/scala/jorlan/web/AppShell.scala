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

import auth.AuthClient
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.User
import jorlan.web.components.{MuiIconButton, MuiListItemButton}
import net.leibman.jorlan.muiMaterial.components.*

import scala.language.unsafeNulls
import scala.scalajs.js

object AppShell {

  private val drawerWidth = 200

  case class Props(
    user:        User,
    currentPage: AppPage,
    navigate:    AppPage => Callback,
    children:    VdomNode,
  )

  val component =
    ScalaFnComponent
      .withHooks[Props]
      .render { props =>
        val navItems: scala.List[AppPage] = AppPage.values.toList

        val drawerSx = js.Dynamic.literal(width = drawerWidth, flexShrink = 0)
        drawerSx.updateDynamic("& .MuiDrawer-paper")(
          js.Dynamic.literal(width = drawerWidth, boxSizing = "border-box"),
        )

        <.div(
          ^.style := js.Dynamic.literal(display = "flex", minHeight = "100vh"),
          AppBar
            .set("position", "fixed")
            .set(
              "sx",
              js.Dynamic.literal(zIndex = 1201, width = s"calc(100% - ${drawerWidth}px)", ml = s"${drawerWidth}px"),
            )(
              Toolbar()(
                Typography
                  .set("variant", "h6")
                  .set("component", "div")
                  .set("sx", js.Dynamic.literal(flexGrow = 1))(
                    s"Jorlan — ${props.currentPage.label}",
                  ),
                Typography
                  .set("variant", "body2")
                  .set("sx", js.Dynamic.literal(mr = 2))(
                    props.user.displayName,
                  ),
                MuiIconButton
                  .onClick(() =>
                    AuthClient
                      .logout()
                      .completeWith(_ => Callback(org.scalajs.dom.window.location.reload()))
                      .runNow(),
                  )
                  .color("inherit")(
                    <.span(^.className := "material-icons")("logout"),
                  ),
              ),
            ),
          Drawer
            .set("variant", "permanent")
            .set("sx", drawerSx)(
              Toolbar(),
              Box()(
                List()(
                  navItems.map { page =>
                    val iconEl: VdomElement = <.span(^.className := "material-icons")(page.icon)
                    ListItem
                      .set("disablePadding", true)
                      .withKey(page.hash)(
                        MuiListItemButton
                          .selected(props.currentPage == page)
                          .onClick(() => props.navigate(page).runNow())(
                            ListItemIcon()(iconEl),
                            ListItemText.primary(page.label)(),
                          ),
                      )
                  }*,
                ),
              ),
            ),
          Box
            .set("component", "main")
            .set(
              "sx",
              js.Dynamic.literal(
                flexGrow = 1,
                p = 3,
                mt = "64px",
              ),
            )(
              props.children,
            ),
        )
      }

  def apply(
    user:        User,
    currentPage: AppPage,
    navigate:    AppPage => Callback,
  )(
    children: VdomNode*,
  ): VdomElement =
    component(
      Props(
        user = user,
        currentPage = currentPage,
        navigate = navigate,
        children = React.Fragment(children*),
      ),
    )

}
