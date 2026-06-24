/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.pages

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.web.AsyncCallbackRepositories
import jorlan.web.components.*
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}
import net.leibman.jorlan.muiMaterial.tableTableMod.TableOwnProps
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme

import scala.language.unsafeNulls
import scala.scalajs.js

object RolesPage {

  case class State(
    roles:                List[Role],
    loading:              Boolean,
    error:                Option[String],
    showCreate:           Boolean,
    createName:           String,
    createDesc:           String,
    editRole:             Option[Role],
    editName:             String,
    editDesc:             String,
    saving:               Boolean,
    deleteTarget:         Option[Role],
    deleting:             Boolean,
    capRole:              Option[Role],
    roleGrants:           List[CapabilityGrant],
    allKnownCapabilities: List[CapabilityName],
    newMode:              String,
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(
        State(
          roles = List.empty,
          loading = true,
          error = None,
          showCreate = false,
          createName = "",
          createDesc = "",
          editRole = None,
          editName = "",
          editDesc = "",
          saving = false,
          deleteTarget = None,
          deleting = false,
          capRole = None,
          roleGrants = List.empty,
          allKnownCapabilities = List.empty,
          newMode = "Persistent",
        ),
      )
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories.permission
              .searchRoles(RoleSearch())
              .flatMap(roles =>
                state.setState(state.value.copy(roles = roles, loading = false)).asAsyncCallback,
              )
              .completeWith(PageUtils.onError(err => state.setState(state.value.copy(loading = false, error = err))))
              .runNow()
          }
      }
      .render {
        (
          _,
          state,
        ) =>

          def reload(): Callback =
            Callback {
              AsyncCallbackRepositories.permission
                .searchRoles(RoleSearch())
                .flatMap(roles => state.setState(state.value.copy(roles = roles)).asAsyncCallback)
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }

          def saveCreate(): Callback =
            Callback {
              state.setState(state.value.copy(saving = true)).runNow()
              AsyncCallbackRepositories.permission
                .upsertRole(Role(RoleId.empty, state.value.createName.trim, Some(state.value.createDesc.trim).filter(_.nonEmpty)))
                .flatMap(_ => AsyncCallbackRepositories.permission.searchRoles(RoleSearch()))
                .flatMap(roles =>
                  state
                    .setState(state.value.copy(saving = false, showCreate = false, createName = "", createDesc = "", roles = roles))
                    .asAsyncCallback,
                )
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(saving = false, error = err))))
                .runNow()
            }

          def saveEdit(): Callback =
            state.value.editRole match {
              case None => Callback.empty
              case Some(role) =>
                Callback {
                  state.setState(state.value.copy(saving = true)).runNow()
                  AsyncCallbackRepositories.permission
                    .upsertRole(Role(role.id, state.value.editName.trim, Some(state.value.editDesc.trim).filter(_.nonEmpty)))
                    .flatMap(_ =>
                      state
                        .setState(state.value.copy(saving = false, editRole = None))
                        .asAsyncCallback
                        .flatMap(_ => reload().asAsyncCallback),
                    )
                    .completeWith(PageUtils.onError(err => state.setState(state.value.copy(saving = false, error = err))))
                    .runNow()
                }
            }

          def deleteRole(role: Role): Callback =
            Callback {
              state.setState(state.value.copy(deleting = true)).runNow()
              AsyncCallbackRepositories.permission
                .deleteRole(role.id)
                .flatMap(_ =>
                  state
                    .setState(state.value.copy(deleting = false, deleteTarget = None))
                    .asAsyncCallback
                    .flatMap(_ => reload().asAsyncCallback),
                )
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(deleting = false, error = err))))
                .runNow()
            }

          def openCaps(role: Role): Callback =
            Callback {
              (AsyncCallbackRepositories.permission.searchGrants(GrantSearch(roleId = Some(role.id))) zip
                AsyncCallbackRepositories.allKnownCapabilities())
                .flatMap { case (grants, caps) =>
                  state
                    .setState(state.value.copy(capRole = Some(role), roleGrants = grants, allKnownCapabilities = caps, newMode = "Persistent"))
                    .asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }

          def closeCaps(): Callback =
            state.setState(state.value.copy(capRole = None, roleGrants = List.empty))

          def grantCapability(capName: CapabilityName): Callback =
            state.value.capRole match {
              case None       => Callback.empty
              case Some(role) =>
                Callback {
                  import java.time.Instant
                  AsyncCallbackRepositories.permission
                    .upsertCapabilityGrant(
                      CapabilityGrant(
                        id = CapabilityGrantId.empty,
                        capability = capName,
                        scopeJson = None,
                        granteeId = role.id.value,
                        granteeType = GranteeType.Role,
                        grantorId = None,
                        approvalMode = ApprovalMode.values
                          .find(_.toString.equalsIgnoreCase(state.value.newMode))
                          .getOrElse(ApprovalMode.Persistent),
                        expiresAt = None,
                        resourceConstraints = None,
                        createdAt = Instant.now(),
                      ),
                    )
                    .flatMap(_ =>
                      AsyncCallbackRepositories.permission
                        .searchGrants(GrantSearch(roleId = Some(role.id)))
                        .flatMap(grants => state.setState(state.value.copy(roleGrants = grants)).asAsyncCallback),
                    )
                    .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                    .runNow()
                }
            }

          def revokeRoleGrant(grantId: CapabilityGrantId): Callback =
            state.value.capRole match {
              case None       => Callback.empty
              case Some(role) =>
                Callback {
                  AsyncCallbackRepositories.permission
                    .revokeGrant(grantId)
                    .flatMap(_ =>
                      AsyncCallbackRepositories.permission
                        .searchGrants(GrantSearch(roleId = Some(role.id)))
                        .flatMap(grants => state.setState(state.value.copy(roleGrants = grants)).asAsyncCallback),
                    )
                    .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                    .runNow()
                }
            }

          <.div(
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic.literal(display = "flex", justifyContent = "space-between", alignItems = "center", mb = 2)
                    .asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography.withProps(TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props])("Roles"),
              MuiButton
                .variant("contained")
                .onClick(() => state.setState(state.value.copy(showCreate = true, createName = "", createDesc = "", error = None)).runNow())(
                  "+ New Role",
                ),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
            if (state.value.loading) {
              Typography.withProps(TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props])("Loading...")
            } else if (state.value.roles.isEmpty) {
              Typography.withProps(
                TypographyOwnProps()
                  .setVariant("body2").setSx(
                    js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                  ).asInstanceOf[Typography.Props],
              )("No roles defined.")
            } else {
              Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
                TableHead()(
                  TableRow()(
                    TableCell()("Name"),
                    TableCell()("Description"),
                    TableCell()("Actions"),
                  ),
                ),
                TableBody()(
                  state.value.roles.map { r =>
                    TableRow.withKey(r.id.value.toString)(
                      TableCell()(r.name),
                      TableCell()(r.description.getOrElse("—")),
                      TableCell()(
                        MuiButton
                          .size("small")
                          .onClick(() =>
                            state.setState(state.value.copy(editRole = Some(r), editName = r.name, editDesc = r.description.getOrElse(""), error = None)).runNow(),
                          )("Edit"),
                        MuiButton
                          .size("small")
                          .onClick(() => openCaps(r).runNow())("Capabilities"),
                        MuiButton
                          .size("small")
                          .color("error")
                          .onClick(() => state.setState(state.value.copy(deleteTarget = Some(r))).runNow())("Delete"),
                      ),
                    )
                  }*,
                ),
              )
            },
            Dialog(state.value.showCreate)(
              DialogTitle()("New Role"),
              DialogContent()(
                state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
                MuiTextField
                  .label("Name")
                  .value(state.value.createName)
                  .fullWidth(true)
                  .variant("outlined")
                  .sx(js.Dynamic.literal(mt = 1, mb = 1))
                  .onChange(e => state.setState(state.value.copy(createName = e.target.value.asInstanceOf[String])).runNow()),
                MuiTextField
                  .label("Description")
                  .value(state.value.createDesc)
                  .fullWidth(true)
                  .variant("outlined")
                  .onChange(e => state.setState(state.value.copy(createDesc = e.target.value.asInstanceOf[String])).runNow()),
              ),
              DialogActions()(
                MuiButton.variant("text").onClick(() => state.setState(state.value.copy(showCreate = false)).runNow())("Cancel"),
                MuiButton
                  .variant("contained")
                  .disabled(state.value.saving || state.value.createName.trim.isEmpty)
                  .onClick(() => saveCreate().runNow())("Create"),
              ),
            ),
            Dialog(state.value.editRole.isDefined)(
              DialogTitle()(s"Edit Role — ${state.value.editRole.map(_.name).getOrElse("")}"),
              DialogContent()(
                state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
                MuiTextField
                  .label("Name")
                  .value(state.value.editName)
                  .fullWidth(true)
                  .variant("outlined")
                  .sx(js.Dynamic.literal(mt = 1, mb = 1))
                  .onChange(e => state.setState(state.value.copy(editName = e.target.value.asInstanceOf[String])).runNow()),
                MuiTextField
                  .label("Description")
                  .value(state.value.editDesc)
                  .fullWidth(true)
                  .variant("outlined")
                  .onChange(e => state.setState(state.value.copy(editDesc = e.target.value.asInstanceOf[String])).runNow()),
              ),
              DialogActions()(
                MuiButton.variant("text").onClick(() => state.setState(state.value.copy(editRole = None)).runNow())("Cancel"),
                MuiButton
                  .variant("contained")
                  .disabled(state.value.saving || state.value.editName.trim.isEmpty)
                  .onClick(() => saveEdit().runNow())("Save"),
              ),
            ),
            Dialog(state.value.deleteTarget.isDefined)(
              DialogTitle()("Delete Role"),
              DialogContent()(
                Typography.withProps(TypographyOwnProps().setVariant("body1").asInstanceOf[Typography.Props])(
                  s"Delete role '${state.value.deleteTarget.map(_.name).getOrElse("")}'? This cannot be undone.",
                ),
              ),
              DialogActions()(
                MuiButton.variant("text").onClick(() => state.setState(state.value.copy(deleteTarget = None)).runNow())("Cancel"),
                MuiButton
                  .variant("contained")
                  .color("error")
                  .disabled(state.value.deleting)
                  .onClick(() => state.value.deleteTarget.fold(Callback.empty)(deleteRole).runNow())("Delete"),
              ),
            ),
            Dialog(state.value.capRole.isDefined)(
              DialogTitle()(s"Capabilities — ${state.value.capRole.map(_.name).getOrElse("")}"),
              DialogContent()(
                state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant("subtitle2").setSx(
                      js.Dynamic.literal(mb = 1).asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Typography.Props],
                )("Existing grants:"),
                if (state.value.roleGrants.isEmpty)
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("body2").setSx(
                        js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )("No capability grants.")
                else
                  Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
                    TableHead()(
                      TableRow()(
                        TableCell()("Capability"),
                        TableCell()("Mode"),
                        TableCell()("Actions"),
                      ),
                    ),
                    TableBody()(
                      state.value.roleGrants.map { g =>
                        TableRow.withKey(g.id.value.toString)(
                          TableCell()(g.capability.value),
                          TableCell()(g.approvalMode.toString),
                          TableCell()(
                            MuiButton
                              .size("small")
                              .color("error")
                              .onClick(() => revokeRoleGrant(g.id).runNow())("Revoke"),
                          ),
                        )
                      }*,
                    ),
                  ),
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant("subtitle2").setSx(
                      js.Dynamic.literal(mt = 2, mb = 1).asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Typography.Props],
                )("Grant capability:"),
                Box.withProps(
                  BoxOwnProps[Theme]()
                    .setSx(
                      js.Dynamic.literal(display = "flex", gap = 1, alignItems = "center").asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Box.Props],
                )(
                  MuiSelect
                    .value(state.value.newMode)
                    .size("small")
                    .onChange { e =>
                      state.setState(state.value.copy(newMode = e.target.asInstanceOf[org.scalajs.dom.html.Select].value)).runNow()
                    }(
                      ApprovalMode.values.map(m =>
                        MuiMenuItem.withKey(m.toString).value(m.toString)(m.toString): VdomNode,
                      )*,
                    ),
                ),
                Box.withProps(
                  BoxOwnProps[Theme]()
                    .setSx(
                      js.Dynamic.literal(mt = 1, maxHeight = 300, overflowY = "auto").asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Box.Props],
                )(
                  Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
                    TableBody()(
                      state.value.allKnownCapabilities.map { cap =>
                        val alreadyGranted = state.value.roleGrants.exists(_.capability == cap)
                        TableRow.withKey(cap.value)(
                          TableCell()(cap.value),
                          TableCell()(
                            MuiButton
                              .size("small")
                              .variant(if (alreadyGranted) "outlined" else "contained")
                              .color(if (alreadyGranted) "error" else "primary")
                              .disabled(alreadyGranted)
                              .onClick(() => grantCapability(cap).runNow())(
                                if (alreadyGranted) "Granted" else "Grant",
                              ),
                          ),
                        )
                      }*,
                    ),
                  ),
                ),
              ),
              DialogActions()(
                MuiButton.variant("text").onClick(() => closeCaps().runNow())("Close"),
              ),
            ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
