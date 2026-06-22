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

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.User
import jorlan.web.pages.*
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

  case Chat extends AppPage("#/", "Chat", "chat")
  case Sessions extends AppPage("#/sessions", "Sessions", "list")
  case Approvals extends AppPage("#/approvals", "Approvals", "approval")
  case Memory extends AppPage("#/memory", "Memory", "memory")
  case Scheduler extends AppPage("#/scheduler", "Scheduler", "schedule")
  case EventLog extends AppPage("#/events", "Event Log", "event_note")
  case Skills extends AppPage("#/skills", "Skills", "extension")
  case Users extends AppPage("#/users", "Users", "group")
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
                display = if (state.value.page == AppPage.Chat) "flex" else "none",
                height = "100%",
              ),
            )(
              ChatPage(user),
            ),
            if (state.value.page != AppPage.Chat)
              state.value.page match {
                case AppPage.Chat      => EmptyVdom
                case AppPage.Sessions  => SessionsPage(user)
                case AppPage.Approvals => ApprovalsPage(user)
                case AppPage.Memory    => MemoryPage(user)
                case AppPage.Scheduler => SchedulerPage(user)
                case AppPage.EventLog  => EventLogPage(user)
                case AppPage.Skills    => SkillsPage(user)
                case AppPage.Users     => UsersPage(user)
                case AppPage.Settings  => SettingsPage(user)
                case AppPage.OAuth     => OAuthManagementPage(user)
              }
            else EmptyVdom,
          )
      }

  def apply(user: User): VdomElement = component(user)

}
