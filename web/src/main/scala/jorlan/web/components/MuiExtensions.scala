/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.components

import japgolly.scalajs.react.{Callback, ReactEventFrom, ReactKeyboardEventFromInput}
import net.leibman.jorlan.StBuildingComponent
import net.leibman.jorlan.muiMaterial.components.Select
import net.leibman.jorlan.muiMaterial.selectSelectInputMod.SelectChangeEvent
import org.scalajs.dom.{Element, HTMLInputElement, HTMLTextAreaElement}

import scala.scalajs.js

/** MUI's `Select`, in its default (outlined) variant. ScalablyTyped generates one builder per variant, and only this
  * one can be started without props.
  */
val OutlinedSelect: Select.OutlinedSelectPropsBaseSelectProps.type = Select.OutlinedSelectPropsBaseSelectProps

/** Helpers on top of the generated MUI components in `muiMaterial.components`, which are used directly. */
object MuiExtensions {

  /** The events of `TextField`, whose target is an `<input>`, or a `<textarea>` when `multiline`. */
  type TextFieldEvent = ReactEventFrom[(HTMLTextAreaElement | HTMLInputElement) & Element]

  /** Both elements `TextField` renders have a value. */
  extension (target: (HTMLTextAreaElement | HTMLInputElement) & Element) {

    def value: String = target.asInstanceOf[HTMLInputElement].value

  }

  /** MUI's `SelectChangeEvent<T>` is a union ScalablyTyped can't express, so the generated type lacks `target`. */
  extension [T](event: SelectChangeEvent[T]) {

    def target: js.Dynamic = event.asInstanceOf[js.Dynamic].target

  }

  extension [B <: StBuildingComponent[?]](builder: B) {

    /** `sx` from a plain style object. The generated `sx` wants `SxProps[Theme]`, a union Scala can't build literally.
      */
    def sxStyle(style: js.Object): B = builder.set("sx", style)

    /** The generated `TextField` lacks the DOM events of its root element: `BaseTextFieldProps` extends a conditional
      * type (`StandardProps<FormControlProps, ...>`), which the converter drops.
      */
    def onKeyDown(value: ReactKeyboardEventFromInput => Callback): B =
      builder.set("onKeyDown", js.Any.fromFunction1((e: ReactKeyboardEventFromInput) => value(e).runNow()))

  }

}
