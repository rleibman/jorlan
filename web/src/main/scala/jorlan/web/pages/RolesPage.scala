/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package jorlan.web.pages

import jorlan.web.components.MuiExtensions.*
import net.leibman.jorlan.muiMaterial.muiMaterialStrings as MuiStrings
import net.leibman.jorlan.muiMaterial.components.{Button, MenuItem, TextField}
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
              .flatMap(roles => state.modState(_.copy(roles = roles, loading = false)).asAsyncCallback)
              .completeWith(PageUtils.onError(err => state.modState(_.copy(loading = false, error = err))))
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
                .flatMap(roles => state.modState(_.copy(roles = roles)).asAsyncCallback)
                .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                .runNow()
            }

          def saveCreate(): Callback =
            Callback {
              state.modState(_.copy(saving = true)).runNow()
              AsyncCallbackRepositories.permission
                .upsertRole(
                  Role(RoleId.empty, state.value.createName.trim, Some(state.value.createDesc.trim).filter(_.nonEmpty)),
                )
                .flatMap(_ => AsyncCallbackRepositories.permission.searchRoles(RoleSearch()))
                .flatMap(roles =>
                  state
                    .modState(
                      _.copy(saving = false, showCreate = false, createName = "", createDesc = "", roles = roles),
                    )
                    .asAsyncCallback,
                )
                .completeWith(PageUtils.onError(err => state.modState(_.copy(saving = false, error = err))))
                .runNow()
            }

          def saveEdit(): Callback =
            state.value.editRole match {
              case None       => Callback.empty
              case Some(role) =>
                Callback {
                  state.modState(_.copy(saving = true)).runNow()
                  AsyncCallbackRepositories.permission
                    .upsertRole(
                      Role(role.id, state.value.editName.trim, Some(state.value.editDesc.trim).filter(_.nonEmpty)),
                    )
                    .flatMap(_ =>
                      state
                        .modState(_.copy(saving = false, editRole = None))
                        .asAsyncCallback
                        .flatMap(_ => reload().asAsyncCallback),
                    )
                    .completeWith(
                      PageUtils.onError(err => state.modState(_.copy(saving = false, error = err))),
                    )
                    .runNow()
                }
            }

          def deleteRole(role: Role): Callback =
            Callback {
              state.modState(_.copy(deleting = true)).runNow()
              AsyncCallbackRepositories.permission
                .deleteRole(role.id)
                .flatMap(_ =>
                  state
                    .modState(_.copy(deleting = false, deleteTarget = None))
                    .asAsyncCallback
                    .flatMap(_ => reload().asAsyncCallback),
                )
                .completeWith(PageUtils.onError(err => state.modState(_.copy(deleting = false, error = err))))
                .runNow()
            }

          def openCaps(role: Role): Callback =
            Callback {
              (AsyncCallbackRepositories.permission.searchGrants(GrantSearch(roleId = Some(role.id))) zip
                AsyncCallbackRepositories.allKnownCapabilities())
                .flatMap { case (grants, caps) =>
                  state
                    .modState(
                      _.copy(
                        capRole = Some(role),
                        roleGrants = grants,
                        allKnownCapabilities = caps,
                        newMode = "Persistent",
                      ),
                    )
                    .asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                .runNow()
            }

          def closeCaps(): Callback =
            state.modState(_.copy(capRole = None, roleGrants = List.empty))

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
                        .flatMap(grants => state.modState(_.copy(roleGrants = grants)).asAsyncCallback),
                    )
                    .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
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
                        .flatMap(grants => state.modState(_.copy(roleGrants = grants)).asAsyncCallback),
                    )
                    .completeWith(PageUtils.onError(err => state.modState(_.copy(error = err))))
                    .runNow()
                }
            }

          <.div(
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "flex", justifyContent = "space-between", alignItems = "center", mb = 2)
                    .asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography
                .withProps(TypographyOwnProps().setVariant(MuiStrings.h5).asInstanceOf[Typography.Props])("Roles"),
              Button
                .variant(MuiStrings.contained)
                .onClick(_ =>
                  state
                    .modState(
                      _.copy(showCreate = true, createName = "", createDesc = "", error = None),
                    ),
                )(
                  "+ New Role",
                ),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity(MuiStrings.error)(err)),
            if (state.value.loading) {
              Typography
                .withProps(TypographyOwnProps().setVariant(MuiStrings.body2).asInstanceOf[Typography.Props])(
                  "Loading...",
                )
            } else if (state.value.roles.isEmpty) {
              Typography.withProps(
                TypographyOwnProps()
                  .setVariant(MuiStrings.body2).setSx(
                    js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                  ).asInstanceOf[Typography.Props],
              )("No roles defined.")
            } else {
              Table.withProps(TableOwnProps().setSize(MuiStrings.small).asInstanceOf[Table.Props])(
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
                        Button
                          .size(MuiStrings.small)
                          .onClick(_ =>
                            state
                              .modState(
                                _.copy(
                                  editRole = Some(r),
                                  editName = r.name,
                                  editDesc = r.description.getOrElse(""),
                                  error = None,
                                ),
                              ),
                          )("Edit"),
                        Button
                          .size(MuiStrings.small)
                          .onClick(_ => openCaps(r))("Capabilities"),
                        Button
                          .size(MuiStrings.small)
                          .color(MuiStrings.error)
                          .onClick(_ => state.modState(_.copy(deleteTarget = Some(r))))("Delete"),
                      ),
                    )
                  }*,
                ),
              )
            },
            Dialog(state.value.showCreate)(
              DialogTitle()("New Role"),
              DialogContent()(
                state.value.error.fold(EmptyVdom)(err => Alert.severity(MuiStrings.error)(err)),
                TextField
                  .label("Name")
                  .value(state.value.createName)
                  .fullWidth(true)
                  .variant(MuiStrings.outlined)
                  .sxStyle(js.Dynamic.literal(mt = 1, mb = 1))
                  .onChange(e => state.modState(_.copy(createName = e.target.value.asInstanceOf[String]))),
                TextField
                  .label("Description")
                  .value(state.value.createDesc)
                  .fullWidth(true)
                  .variant(MuiStrings.outlined)
                  .onChange(e => state.modState(_.copy(createDesc = e.target.value.asInstanceOf[String]))),
              ),
              DialogActions()(
                Button
                  .variant(MuiStrings.text).onClick(_ => state.modState(_.copy(showCreate = false)))(
                    "Cancel",
                  ),
                Button
                  .variant(MuiStrings.contained)
                  .disabled(state.value.saving || state.value.createName.trim.isEmpty)
                  .onClick(_ => saveCreate())("Create"),
              ),
            ),
            Dialog(state.value.editRole.isDefined)(
              DialogTitle()(s"Edit Role — ${state.value.editRole.map(_.name).getOrElse("")}"),
              DialogContent()(
                state.value.error.fold(EmptyVdom)(err => Alert.severity(MuiStrings.error)(err)),
                TextField
                  .label("Name")
                  .value(state.value.editName)
                  .fullWidth(true)
                  .variant(MuiStrings.outlined)
                  .sxStyle(js.Dynamic.literal(mt = 1, mb = 1))
                  .onChange(e => state.modState(_.copy(editName = e.target.value.asInstanceOf[String]))),
                TextField
                  .label("Description")
                  .value(state.value.editDesc)
                  .fullWidth(true)
                  .variant(MuiStrings.outlined)
                  .onChange(e => state.modState(_.copy(editDesc = e.target.value.asInstanceOf[String]))),
              ),
              DialogActions()(
                Button
                  .variant(MuiStrings.text).onClick(_ => state.modState(_.copy(editRole = None)))("Cancel"),
                Button
                  .variant(MuiStrings.contained)
                  .disabled(state.value.saving || state.value.editName.trim.isEmpty)
                  .onClick(_ => saveEdit())("Save"),
              ),
            ),
            Dialog(state.value.deleteTarget.isDefined)(
              DialogTitle()("Delete Role"),
              DialogContent()(
                Typography.withProps(TypographyOwnProps().setVariant(MuiStrings.body1).asInstanceOf[Typography.Props])(
                  s"Delete role '${state.value.deleteTarget.map(_.name).getOrElse("")}'? This cannot be undone.",
                ),
              ),
              DialogActions()(
                Button
                  .variant(MuiStrings.text).onClick(_ => state.modState(_.copy(deleteTarget = None)))(
                    "Cancel",
                  ),
                Button
                  .variant(MuiStrings.contained)
                  .color(MuiStrings.error)
                  .disabled(state.value.deleting)
                  .onClick(_ => state.value.deleteTarget.fold(Callback.empty)(deleteRole))("Delete"),
              ),
            ),
            Dialog(state.value.capRole.isDefined)(
              DialogTitle()(s"Capabilities — ${state.value.capRole.map(_.name).getOrElse("")}"),
              DialogContent()(
                state.value.error.fold(EmptyVdom)(err => Alert.severity(MuiStrings.error)(err)),
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant(MuiStrings.subtitle2).setSx(
                      js.Dynamic.literal(mb = 1).asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Typography.Props],
                )("Existing grants:"),
                if (state.value.roleGrants.isEmpty)
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant(MuiStrings.body2).setSx(
                        js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )("No capability grants.")
                else
                  Table.withProps(TableOwnProps().setSize(MuiStrings.small).asInstanceOf[Table.Props])(
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
                            Button
                              .size(MuiStrings.small)
                              .color(MuiStrings.error)
                              .onClick(_ => revokeRoleGrant(g.id))("Revoke"),
                          ),
                        )
                      }*,
                    ),
                  ),
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant(MuiStrings.subtitle2).setSx(
                      js.Dynamic.literal(mt = 2, mb = 1).asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Typography.Props],
                )("Grant capability:"),
                Box.withProps(
                  BoxOwnProps[Theme]()
                    .setSx(
                      js.Dynamic.literal(display = "flex", gap = 1, alignItems = "center").asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Box.Props],
                )(
                  OutlinedSelect
                    .value(state.value.newMode)
                    .size(MuiStrings.small)
                    .onChange {
                      (
                        e,
                        _,
                      ) =>
                        state
                          .modState(
                            _.copy(newMode = e.target.asInstanceOf[org.scalajs.dom.html.Select].value),
                          )
                    }(
                      ApprovalMode.values
                        .map(m => MenuItem.withKey(m.toString).value(m.toString)(m.toString): VdomNode)*,
                    ),
                ),
                Box.withProps(
                  BoxOwnProps[Theme]()
                    .setSx(
                      js.Dynamic.literal(mt = 1, maxHeight = 300, overflowY = "auto").asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Box.Props],
                )(
                  Table.withProps(TableOwnProps().setSize(MuiStrings.small).asInstanceOf[Table.Props])(
                    TableBody()(
                      state.value.allKnownCapabilities.map { cap =>
                        val alreadyGranted = state.value.roleGrants.exists(_.capability == cap)
                        TableRow.withKey(cap.value)(
                          TableCell()(cap.value),
                          TableCell()(
                            Button
                              .size(MuiStrings.small)
                              .variant(if (alreadyGranted) MuiStrings.outlined else MuiStrings.contained)
                              .color(if (alreadyGranted) MuiStrings.error else MuiStrings.primary)
                              .disabled(alreadyGranted)
                              .onClick(_ => grantCapability(cap))(
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
                Button.variant(MuiStrings.text).onClick(_ => closeCaps())("Close"),
              ),
            ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
