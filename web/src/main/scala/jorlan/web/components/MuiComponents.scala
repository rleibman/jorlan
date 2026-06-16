/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web.components

import net.leibman.jorlan.StBuildingComponent
import net.leibman.jorlan.StBuildingComponent.Default
import org.scalablytyped.runtime.StObject
import scala.scalajs.js
import scala.scalajs.js.annotation.{JSImport, JSName}

/** Thin wrappers for MUI v9 components not present in stLib's `components/` package.
  *
  * These are created using the `@JSImport` + `StBuildingComponent.Default` pattern, mirroring how ScalablyTyped
  * generates the `components/` wrappers.
  */
object MuiButton {

  @JSImport("@mui/material", "Button")
  @js.native
  val component: js.Object = js.native

  @scala.inline
  open class Builder(val args: js.Array[Any]) extends AnyVal with StBuildingComponent[js.Object] {

    inline def variant(value:   String):             this.type = set("variant", value.asInstanceOf[js.Any])
    inline def color(value:     String):             this.type = set("color", value.asInstanceOf[js.Any])
    inline def size(value:      String):             this.type = set("size", value.asInstanceOf[js.Any])
    inline def disabled(value:  Boolean):            this.type = set("disabled", value.asInstanceOf[js.Any])
    inline def fullWidth(value: Boolean):            this.type = set("fullWidth", value.asInstanceOf[js.Any])
    inline def onClick(value:   js.Function0[Unit]): this.type = set("onClick", value.asInstanceOf[js.Any])
    inline def `type`(value:    String):             this.type = set("type", value.asInstanceOf[js.Any])
    inline def sx(value:        js.Object):          this.type = set("sx", value.asInstanceOf[js.Any])
    inline def startIcon(value: js.Any):             this.type = set("startIcon", value.asInstanceOf[js.Any])

  }

  implicit def make(companion: MuiButton.type): Builder =
    new Builder(js.Array(this.component, js.Dictionary.empty))()

}

object MuiTextField {

  @JSImport("@mui/material", "TextField")
  @js.native
  val component: js.Object = js.native

  @scala.inline
  open class Builder(val args: js.Array[Any]) extends AnyVal with StBuildingComponent[js.Object] {

    inline def label(value:       String):  this.type = set("label", value.asInstanceOf[js.Any])
    inline def value(value:       String):  this.type = set("value", value.asInstanceOf[js.Any])
    inline def placeholder(value: String):  this.type = set("placeholder", value.asInstanceOf[js.Any])
    inline def variant(value:     String):  this.type = set("variant", value.asInstanceOf[js.Any])
    inline def size(value:        String):  this.type = set("size", value.asInstanceOf[js.Any])
    inline def fullWidth(value:   Boolean): this.type = set("fullWidth", value.asInstanceOf[js.Any])
    inline def multiline(value:   Boolean): this.type = set("multiline", value.asInstanceOf[js.Any])
    inline def rows(value:        Int):     this.type = set("rows", value.asInstanceOf[js.Any])
    inline def maxRows(value:     Int):     this.type = set("maxRows", value.asInstanceOf[js.Any])
    inline def disabled(value:    Boolean): this.type = set("disabled", value.asInstanceOf[js.Any])
    inline def `type`(value:      String):  this.type = set("type", value.asInstanceOf[js.Any])
    inline def autoFocus(value:   Boolean): this.type = set("autoFocus", value.asInstanceOf[js.Any])
    inline def onChange(value: js.Function1[js.Dynamic, Unit]):  this.type = set("onChange", value.asInstanceOf[js.Any])
    inline def onKeyDown(value: js.Function1[js.Dynamic, Unit]): this.type =
      set("onKeyDown", value.asInstanceOf[js.Any])
    inline def sx(value:         js.Object): this.type = set("sx", value.asInstanceOf[js.Any])
    inline def inputProps(value: js.Object): this.type = set("inputProps", value.asInstanceOf[js.Any])
    inline def slotProps(value:  js.Object): this.type = set("slotProps", value.asInstanceOf[js.Any])

  }

  implicit def make(companion: MuiTextField.type): Builder =
    new Builder(js.Array(this.component, js.Dictionary.empty))()

}

object MuiSelect {

  @JSImport("@mui/material", "Select")
  @js.native
  val component: js.Object = js.native

  @scala.inline
  open class Builder(val args: js.Array[Any]) extends AnyVal with StBuildingComponent[js.Object] {

    inline def value(value:     String):  this.type = set("value", value.asInstanceOf[js.Any])
    inline def label(value:     String):  this.type = set("label", value.asInstanceOf[js.Any])
    inline def variant(value:   String):  this.type = set("variant", value.asInstanceOf[js.Any])
    inline def fullWidth(value: Boolean): this.type = set("fullWidth", value.asInstanceOf[js.Any])
    inline def onChange(value: js.Function1[js.Dynamic, Unit]): this.type = set("onChange", value.asInstanceOf[js.Any])
    inline def sx(value:       js.Object):                      this.type = set("sx", value.asInstanceOf[js.Any])

  }

  implicit def make(companion: MuiSelect.type): Builder =
    new Builder(js.Array(this.component, js.Dictionary.empty))()

}

object MuiMenuItem {

  @JSImport("@mui/material", "MenuItem")
  @js.native
  val component: js.Object = js.native

  @scala.inline
  open class Builder(val args: js.Array[Any]) extends AnyVal with StBuildingComponent[js.Object] {

    inline def value(value:    String):             this.type = set("value", value.asInstanceOf[js.Any])
    inline def disabled(value: Boolean):            this.type = set("disabled", value.asInstanceOf[js.Any])
    inline def onClick(value:  js.Function0[Unit]): this.type = set("onClick", value.asInstanceOf[js.Any])
    inline def sx(value:       js.Object):          this.type = set("sx", value.asInstanceOf[js.Any])

  }

  implicit def make(companion: MuiMenuItem.type): Builder =
    new Builder(js.Array(this.component, js.Dictionary.empty))()

}

object MuiIconButton {

  @JSImport("@mui/material", "IconButton")
  @js.native
  val component: js.Object = js.native

  @scala.inline
  open class Builder(val args: js.Array[Any]) extends AnyVal with StBuildingComponent[js.Object] {

    inline def onClick(value:  js.Function0[Unit]): this.type = set("onClick", value.asInstanceOf[js.Any])
    inline def color(value:    String):             this.type = set("color", value.asInstanceOf[js.Any])
    inline def size(value:     String):             this.type = set("size", value.asInstanceOf[js.Any])
    inline def disabled(value: Boolean):            this.type = set("disabled", value.asInstanceOf[js.Any])
    inline def edge(value:     String):             this.type = set("edge", value.asInstanceOf[js.Any])
    inline def sx(value:       js.Object):          this.type = set("sx", value.asInstanceOf[js.Any])

  }

  implicit def make(companion: MuiIconButton.type): Builder =
    new Builder(js.Array(this.component, js.Dictionary.empty))()

}

object MuiIcon {

  @JSImport("@mui/material", "Icon")
  @js.native
  val component: js.Object = js.native

  @scala.inline
  open class Builder(val args: js.Array[Any]) extends AnyVal with StBuildingComponent[js.Object] {

    inline def color(value: String):    this.type = set("color", value.asInstanceOf[js.Any])
    inline def sx(value:    js.Object): this.type = set("sx", value.asInstanceOf[js.Any])

  }

  implicit def make(companion: MuiIcon.type): Builder =
    new Builder(js.Array(this.component, js.Dictionary.empty))()

}

object MuiTablePagination {

  @JSImport("@mui/material", "TablePagination")
  @js.native
  val component: js.Object = js.native

  @scala.inline
  open class Builder(val args: js.Array[Any]) extends AnyVal with StBuildingComponent[js.Object] {

    inline def count(value:       Int):                  this.type = set("count", value.asInstanceOf[js.Any])
    inline def page(value:        Int):                  this.type = set("page", value.asInstanceOf[js.Any])
    inline def rowsPerPage(value: Int):                  this.type = set("rowsPerPage", value.asInstanceOf[js.Any])
    inline def component(value:   String):               this.type = set("component", value.asInstanceOf[js.Any])
    inline def rowsPerPageOptions(value: js.Array[Int]): this.type =
      set("rowsPerPageOptions", value.asInstanceOf[js.Any])
    inline def onPageChange(value: js.Function2[js.Dynamic, Int, Unit]): this.type =
      set("onPageChange", value.asInstanceOf[js.Any])
    inline def onRowsPerPageChange(value: js.Function1[js.Dynamic, Unit]): this.type =
      set("onRowsPerPageChange", value.asInstanceOf[js.Any])

  }

  implicit def make(companion: MuiTablePagination.type): Builder =
    new Builder(js.Array(this.component, js.Dictionary.empty))()

}

object MuiListItemButton {

  @JSImport("@mui/material", "ListItemButton")
  @js.native
  val component: js.Object = js.native

  @scala.inline
  open class Builder(val args: js.Array[Any]) extends AnyVal with StBuildingComponent[js.Object] {

    inline def selected(value: Boolean):            this.type = set("selected", value.asInstanceOf[js.Any])
    inline def onClick(value:  js.Function0[Unit]): this.type = set("onClick", value.asInstanceOf[js.Any])
    inline def sx(value:       js.Object):          this.type = set("sx", value.asInstanceOf[js.Any])

  }

  implicit def make(companion: MuiListItemButton.type): Builder =
    new Builder(js.Array(this.component, js.Dictionary.empty))()

}
