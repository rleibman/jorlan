/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.components

import net.leibman.jorlan.muiMaterial.muiMaterialStrings as MuiStrings
import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import net.leibman.jorlan.muiMaterial.components.{Alert, Snackbar}

import scala.language.unsafeNulls
import scala.scalajs.js

/** Toast severity levels. */
enum ToastSeverity {

  case Success, Info, Warning, Error

}

case class ToastMessage(
  message:  String,
  severity: ToastSeverity = ToastSeverity.Info,
)

/** Simple MUI Snackbar-based toast notification. */
object Toast {

  case class Props(
    message: Option[ToastMessage],
    onClose: Callback,
  )

  val component =
    ScalaFnComponent[Props] { props =>
      val severity: MuiStrings.success | MuiStrings.warning | MuiStrings.error | MuiStrings.info =
        props.message.map(_.severity) match {
          case Some(ToastSeverity.Success) => MuiStrings.success
          case Some(ToastSeverity.Warning) => MuiStrings.warning
          case Some(ToastSeverity.Error)   => MuiStrings.error
          case _                           => MuiStrings.info
        }

      Snackbar
        .open(props.message.isDefined)
        .autoHideDuration(4000)
        .onClose(
          (
            _,
            _,
          ) => props.onClose,
        )(
          Alert
            .severity(severity)(
              props.message.map(_.message).getOrElse(""),
            ),
        )
        .build
    }

  def apply(
    message: Option[ToastMessage],
    onClose: Callback,
  ): VdomElement =
    component(Props(message, onClose))

}
