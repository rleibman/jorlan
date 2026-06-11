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
import jorlan.domain.User
import jorlan.web.pages.*

import scala.language.unsafeNulls
import scala.scalajs.js

enum AppPage(
  val hash:  String,
  val label: String,
) {

  case Chat extends AppPage("#/", "Chat")
  case Sessions extends AppPage("#/sessions", "Sessions")
  case Approvals extends AppPage("#/approvals", "Approvals")
  case Memory extends AppPage("#/memory", "Memory")
  case Scheduler extends AppPage("#/scheduler", "Scheduler")
  case EventLog extends AppPage("#/events", "Event Log")
  case Skills extends AppPage("#/skills", "Skills")
  case Users extends AppPage("#/users", "Users")
  case Settings extends AppPage("#/settings", "Settings")

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
      .useEffect(Callback.empty) // hash change listener placeholder
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
            state.value.page match {
              case AppPage.Chat      => ChatPage(user)
              case AppPage.Sessions  => SessionsPage(user)
              case AppPage.Approvals => ApprovalsPage(user)
              case AppPage.Memory    => MemoryPage(user)
              case AppPage.Scheduler => SchedulerPage(user)
              case AppPage.EventLog  => EventLogPage(user)
              case AppPage.Skills    => SkillsPage(user)
              case AppPage.Users     => UsersPage(user)
              case AppPage.Settings  => SettingsPage(user)
            },
          )
      }

  def apply(user: User): VdomElement = component(user)

}
