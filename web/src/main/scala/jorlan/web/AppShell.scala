/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web

import auth.AuthClient
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.User
import jorlan.web.components.{MuiIconButton, MuiListItemButton}
import net.leibman.jorlan.muiMaterial.appBarAppBarMod.AppBarOwnProps
import net.leibman.jorlan.muiMaterial.components.*
import net.leibman.jorlan.muiMaterial.listItemListItemMod.ListItemOwnProps
import net.leibman.jorlan.muiMaterial.muiMaterialStrings as MuiStrings
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

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
          AppBar.withProps(
            AppBarOwnProps()
              .setPosition(MuiStrings.fixed)
              .setSx(
                js.Dynamic
                  .literal(zIndex = 1201, width = s"calc(100% - ${drawerWidth}px)", ml = s"${drawerWidth}px")
                  .asInstanceOf[SxProps[Theme]],
              )
              .asInstanceOf[AppBar.Props],
          )(
            Toolbar()(
              Typography.withProps(
                TypographyOwnProps()
                  .setVariant("h6")
                  .setSx(js.Dynamic.literal(flexGrow = 1).asInstanceOf[SxProps[Theme]])
                  .asInstanceOf[Typography.Props],
              )(
                s"Jorlan — ${props.currentPage.label}",
              ),
              Typography.withProps(
                TypographyOwnProps()
                  .setVariant("body2")
                  .setSx(js.Dynamic.literal(mr = 2).asInstanceOf[SxProps[Theme]])
                  .asInstanceOf[Typography.Props],
              )(
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
            .variant(MuiStrings.permanent)
            .sx(drawerSx.asInstanceOf[SxProps[Theme]])(
              Toolbar(),
              Box()(
                List()(
                  navItems.map { page =>
                    val iconEl: VdomElement = <.span(^.className := "material-icons")(page.icon)
                    ListItem
                      .withProps(
                        ListItemOwnProps()
                          .setDisablePadding(true)
                          .asInstanceOf[ListItem.Props],
                      ).withKey(page.hash)(
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
          Box.withProps(
            js.Dynamic
              .literal(
                component = "main",
                sx = js.Dynamic
                  .literal(
                    flexGrow = 1,
                    p = 3,
                    mt = "64px",
                  )
                  .asInstanceOf[SxProps[Theme]],
              )
              .asInstanceOf[Box.Props],
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
