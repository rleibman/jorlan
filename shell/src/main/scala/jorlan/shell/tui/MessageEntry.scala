/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.shell.tui

enum MessageKind {

  case User // line typed by the human
  case Server // response from Jorlan
  case System // shell/connection status
  case Error // error condition
  case Raw // pre-formatted text — rendered verbatim, no timestamp or prefix

}

case class MessageEntry(
  kind:    MessageKind,
  content: String,
  time:    String,
)
