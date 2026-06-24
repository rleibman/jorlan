/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.User
import jorlan.web.pages.*
import jorlan.web.pages.DashboardPage
import org.scalajs.dom

import scala.language.unsafeNulls
import scala.scalajs.js

/** A page in the single-page application identified by its URL hash fragment.
  *
  * @param hash
  *   The `window.location.hash` value that activates this page (e.g. `"#/sessions"`). The empty/root hash `"#/"` maps
  *   to [[Chat]].
  * @param label
  *   Human-readable name shown in the navigation drawer and app-bar title.
  * @param icon
  *   Material Icons ligature string rendered as `<span class="material-icons">icon</span>` in the nav drawer.
  */
enum AppPage(
  val hash:  String,
  val label: String,
  val icon:  String,
) {

  case Dashboard extends AppPage("#/dashboard", "Dashboard", "dashboard")
  case Chat extends AppPage("#/", "Chat", "chat")
  case Sessions extends AppPage("#/sessions", "Sessions", "list")
  case Approvals extends AppPage("#/approvals", "Approvals", "approval")
  case Memory extends AppPage("#/memory", "Memory", "memory")
  case Scheduler extends AppPage("#/scheduler", "Scheduler", "schedule")
  case EventLog extends AppPage("#/events", "Event Log", "event_note")
  case Skills extends AppPage("#/skills", "Skills", "extension")
  case McpServers extends AppPage("#/mcp", "MCP Servers", "hub")
  case Users extends AppPage("#/users", "Users", "group")
  case Roles extends AppPage("#/roles", "Roles", "badge")
  case Settings extends AppPage("#/settings", "Settings", "settings")
  case OAuth extends AppPage("#/oauth", "Connected Accounts", "link")

}

object AppRouter {

  private def currentPage: AppPage = {
    val hash = org.scalajs.dom.window.location.hash
    AppPage.values.find(_.hash == hash).getOrElse(AppPage.Chat)
  }

  case class State(page: AppPage)

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(currentPage))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            // Sync React state when the user navigates via browser back/forward or direct URL entry
            val listener: js.Function1[dom.HashChangeEvent, Unit] =
              (_: dom.HashChangeEvent) => state.setState(State(currentPage)).runNow()
            dom.window.addEventListener("hashchange", listener)
          }
      }
      .render {
        (
          user,
          state,
        ) =>
          val navigate: AppPage => Callback = page =>
            Callback {
              org.scalajs.dom.window.location.hash = page.hash
            } >> state.setState(State(page))

          AppShell(
            user = user,
            currentPage = state.value.page,
            navigate = navigate,
          )(
            // ChatPage stays mounted at all times so its session connection, message history,
            // and in-progress prompt text survive page navigation. CSS hides it when inactive.
            <.div(
              ^.style := js.Dynamic.literal(
                display = if (state.value.page == AppPage.Chat) "block" else "none",
              ),
            )(
              ChatPage(user),
            ),
            // EventLogPage stays mounted to preserve its WebSocket connection and accumulated log entries.
            <.div(
              ^.style := js.Dynamic.literal(
                display = if (state.value.page == AppPage.EventLog) "block" else "none",
              ),
            )(
              EventLogPage(user),
            ),
            if (state.value.page != AppPage.Chat && state.value.page != AppPage.EventLog)
              state.value.page match {
                case AppPage.Dashboard  => DashboardPage(user)
                case AppPage.Chat       => EmptyVdom
                case AppPage.Sessions   => SessionsPage(user)
                case AppPage.Approvals  => ApprovalsPage(user)
                case AppPage.Memory     => MemoryPage(user)
                case AppPage.Scheduler  => SchedulerPage(user)
                case AppPage.EventLog   => EmptyVdom
                case AppPage.Skills     => SkillsPage(user)
                case AppPage.McpServers => McpServersPage(user)
                case AppPage.Users      => UsersPage(user)
                case AppPage.Roles      => RolesPage(user)
                case AppPage.Settings   => SettingsPage(user)
                case AppPage.OAuth      => OAuthManagementPage(user)
              }
            else EmptyVdom,
          )
      }

  def apply(user: User): VdomElement = component(user)

}
