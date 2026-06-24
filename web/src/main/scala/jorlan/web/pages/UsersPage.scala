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
import jorlan.web.components.{MuiButton, MuiMenuItem, MuiSelect, MuiTablePagination, MuiTextField}
import jorlan.web.pages.PageUtils
import net.leibman.jorlan.muiMaterial.components.{List as MuiList, *}

import net.leibman.jorlan.muiMaterial.chipChipMod.ChipOwnProps
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.tableTableMod.TableOwnProps
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps

import scala.language.unsafeNulls
import scala.scalajs.js

object UsersPage {

  case class EditState(
    displayName: String,
    email:       String,
  )

  case class State(
    users:        List[User],
    loading:      Boolean,
    error:        Option[String],
    page:         Int,
    rowsPerPage:  Int,
    editingUser:  Option[User],
    editState:    Option[EditState],
    saving:       Boolean,
    createOpen:   Boolean,
    createName:   String,
    createEmail:  String,
    permsUser:            Option[User],
    grants:               List[CapabilityGrant],
    allKnownCapabilities: List[CapabilityName],
    newMode:              String,
    rolesUser:            Option[User],
    userRoles:    List[Role],
    allRoles:     List[Role],
    assignRoleId: String,
    identsUser:   Option[User],
    identities:   List[ChannelIdentity],
    newChType:    String,
    newChUserId:  String,
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(
        State(
          users = List.empty,
          loading = true,
          error = None,
          page = 0,
          rowsPerPage = 10,
          editingUser = None,
          editState = None,
          saving = false,
          createOpen = false,
          createName = "",
          createEmail = "",
          permsUser = None,
          grants = List.empty,
          allKnownCapabilities = List.empty,
          newMode = "Persistent",
          rolesUser = None,
          userRoles = List.empty,
          allRoles = List.empty,
          assignRoleId = "",
          identsUser = None,
          identities = List.empty,
          newChType = "Telegram",
          newChUserId = "",
        ),
      )
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories.user
              .search(UserSearch())
              .flatMap(users =>
                state
                  .setState(
                    state.value.copy(users = users, loading = false, error = None, page = 0),
                  ).asAsyncCallback,
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
          val pageItems = state.value.users
            .slice(state.value.page * state.value.rowsPerPage, (state.value.page + 1) * state.value.rowsPerPage)

          def reload(): Callback =
            Callback {
              AsyncCallbackRepositories.user
                .search(UserSearch())
                .flatMap(users =>
                  state
                    .setState(state.value.copy(users = users, error = None, editingUser = None, editState = None))
                    .asAsyncCallback,
                )
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }

          def handleDeactivate(user: User): Callback =
            Callback {
              AsyncCallbackRepositories.user
                .deactivate(user.id)
                .flatMap { count =>
                  if (count > 0) reload().asAsyncCallback
                  else
                    state
                      .setState(state.value.copy(error = Some(s"Could not deactivate ${user.displayName}")))
                      .asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }

          def handleReactivate(user: User): Callback =
            Callback {
              AsyncCallbackRepositories.user
                .upsert(user.copy(active = true))
                .flatMap(_ => reload().asAsyncCallback)
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }

          def openEdit(user: User): Callback =
            state.setState(
              state.value.copy(
                editingUser = Some(user),
                editState = Some(EditState(user.displayName, user.email)),
              ),
            )

          def closeEdit(): Callback =
            state.setState(state.value.copy(editingUser = None, editState = None))

          def saveEdit(): Callback =
            (state.value.editingUser, state.value.editState) match {
              case (Some(user), Some(es)) =>
                Callback {
                  state.setState(state.value.copy(saving = true)).runNow()
                  AsyncCallbackRepositories.user
                    .upsert(user.copy(displayName = es.displayName, email = es.email))
                    .flatMap(_ =>
                      state
                        .setState(state.value.copy(saving = false, editingUser = None, editState = None))
                        .asAsyncCallback
                        .flatMap(_ => reload().asAsyncCallback),
                    )
                    .completeWith(
                      PageUtils.onError(err => state.setState(state.value.copy(saving = false, error = err))),
                    )
                    .runNow()
                }
              case _ => Callback.empty
            }

          def openCreate(): Callback =
            state.setState(state.value.copy(createOpen = true, createName = "", createEmail = ""))

          def closeCreate(): Callback =
            state.setState(state.value.copy(createOpen = false))

          def saveCreate(): Callback =
            Callback {
              import java.time.Instant
              state.setState(state.value.copy(saving = true)).runNow()
              AsyncCallbackRepositories.user
                .upsert(
                  User(
                    id = UserId.empty,
                    displayName = state.value.createName,
                    email = state.value.createEmail,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                    active = true,
                  ),
                )
                .flatMap(_ =>
                  state
                    .setState(state.value.copy(saving = false, createOpen = false))
                    .asAsyncCallback
                    .flatMap(_ => reload().asAsyncCallback),
                )
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(saving = false, error = err))))
                .runNow()
            }

          def openPerms(user: User): Callback =
            Callback {
              (AsyncCallbackRepositories.permission.searchGrants(GrantSearch(userId = Some(user.id))) zip
                AsyncCallbackRepositories.allKnownCapabilities())
                .flatMap { case (grants, caps) =>
                  state
                    .setState(
                      state.value.copy(
                        permsUser = Some(user),
                        grants = grants,
                        allKnownCapabilities = caps,
                        newMode = "Persistent",
                      ),
                    ).asAsyncCallback
                }
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }

          def closePerms(): Callback =
            state.setState(state.value.copy(permsUser = None, grants = List.empty))

          def grantCapability(capName: CapabilityName): Callback =
            state.value.permsUser match {
              case None       => Callback.empty
              case Some(user) =>
                Callback {
                  import java.time.Instant
                  AsyncCallbackRepositories.permission
                    .upsertCapabilityGrant(
                      CapabilityGrant(
                        id = CapabilityGrantId.empty,
                        capability = capName,
                        scopeJson = None,
                        granteeId = user.id.value,
                        granteeType = GranteeType.User,
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
                        .searchGrants(GrantSearch(userId = Some(user.id)))
                        .flatMap(grants =>
                          state
                            .setState(
                              state.value.copy(grants = grants, newMode = "Persistent"),
                            ).asAsyncCallback,
                        ),
                    )
                    .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                    .runNow()
                }
            }

          def revokeGrant(grantId: CapabilityGrantId): Callback =
            state.value.permsUser match {
              case None       => Callback.empty
              case Some(user) =>
                Callback {
                  AsyncCallbackRepositories.permission
                    .revokeGrant(grantId)
                    .flatMap(_ =>
                      AsyncCallbackRepositories.permission
                        .searchGrants(GrantSearch(userId = Some(user.id)))
                        .flatMap(grants => state.setState(state.value.copy(grants = grants)).asAsyncCallback),
                    )
                    .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                    .runNow()
                }
            }

          def openRoles(user: User): Callback =
            Callback {
              (
                AsyncCallbackRepositories.permission.searchRoles(RoleSearch(userId = Some(user.id))) zip
                  AsyncCallbackRepositories.permission.searchRoles(RoleSearch(userId = None))
              ).flatMap { case (userRoles, allRoles) =>
                state
                  .setState(
                    state.value
                      .copy(rolesUser = Some(user), userRoles = userRoles, allRoles = allRoles, assignRoleId = ""),
                  ).asAsyncCallback
              }
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }

          def closeRoles(): Callback =
            state.setState(state.value.copy(rolesUser = None, userRoles = List.empty, allRoles = List.empty))

          def assignRole(): Callback =
            (state.value.rolesUser, state.value.assignRoleId.toLongOption) match {
              case (Some(user), Some(rid)) =>
                Callback {
                  AsyncCallbackRepositories.permission
                    .assignRole(user.id, RoleId(rid))
                    .flatMap(_ =>
                      AsyncCallbackRepositories.permission
                        .searchRoles(RoleSearch(userId = Some(user.id)))
                        .flatMap(roles =>
                          state.setState(state.value.copy(userRoles = roles, assignRoleId = "")).asAsyncCallback,
                        ),
                    )
                    .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                    .runNow()
                }
              case _ => Callback.empty
            }

          def removeRole(roleId: RoleId): Callback =
            state.value.rolesUser match {
              case None       => Callback.empty
              case Some(user) =>
                Callback {
                  AsyncCallbackRepositories.permission
                    .removeRole(user.id, roleId)
                    .flatMap(_ =>
                      AsyncCallbackRepositories.permission
                        .searchRoles(RoleSearch(userId = Some(user.id)))
                        .flatMap(roles => state.setState(state.value.copy(userRoles = roles)).asAsyncCallback),
                    )
                    .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                    .runNow()
                }
            }

          def openIdentities(user: User): Callback =
            Callback {
              AsyncCallbackRepositories.user
                .getChannelIdentities(user.id)
                .flatMap(identities =>
                  state
                    .setState(
                      state.value.copy(
                        identsUser = Some(user),
                        identities = identities,
                        newChType = "Telegram",
                        newChUserId = "",
                        error = None,
                      ),
                    ).asAsyncCallback,
                )
                .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                .runNow()
            }

          def closeIdentities(): Callback =
            state.setState(state.value.copy(identsUser = None, identities = List.empty))

          def linkIdentity(): Callback =
            state.value.identsUser match {
              case None       => Callback.empty
              case Some(user) =>
                Callback {
                  import java.time.Instant
                  val chTypeOpt = ChannelType.values.find(_.toString.equalsIgnoreCase(state.value.newChType))
                  chTypeOpt.foreach { chType =>
                    AsyncCallbackRepositories.user
                      .upsertChannelIdentity(
                        ChannelIdentity(
                          id = ChannelIdentityId.empty,
                          userId = user.id,
                          channelType = chType,
                          channelUserId = state.value.newChUserId,
                          verified = false,
                          providerData = None,
                          createdAt = Instant.now(),
                        ),
                      )
                      .flatMap(_ =>
                        AsyncCallbackRepositories.user
                          .getChannelIdentities(user.id)
                          .flatMap(ids =>
                            state
                              .setState(
                                state.value.copy(identities = ids, newChType = "Telegram", newChUserId = ""),
                              ).asAsyncCallback,
                          ),
                      )
                      .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                      .runNow()
                  }
                }
            }

          def unlinkIdentity(identityId: ChannelIdentityId): Callback =
            state.value.identsUser match {
              case None       => Callback.empty
              case Some(user) =>
                Callback {
                  AsyncCallbackRepositories.user
                    .deleteChannelIdentity(identityId)
                    .flatMap(_ =>
                      AsyncCallbackRepositories.user
                        .getChannelIdentities(user.id)
                        .flatMap(ids => state.setState(state.value.copy(identities = ids)).asAsyncCallback),
                    )
                    .completeWith(PageUtils.onError(err => state.setState(state.value.copy(error = err))))
                    .runNow()
                }
            }

          val isEditOpen = state.value.editingUser.isDefined
          val editDisplayName = state.value.editState.map(_.displayName).getOrElse("")
          val editEmail = state.value.editState.map(_.email).getOrElse("")

          <.div(
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "flex", alignItems = "center", mb = 2, gap = 2).asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              Typography.withProps(TypographyOwnProps().setVariant("h5").asInstanceOf[Typography.Props])("Users"),
              MuiButton.variant("contained").onClick(() => openCreate().runNow())("Create User"),
            ),
            state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
            if (state.value.loading) CircularProgress()
            else if (state.value.users.isEmpty)
              Alert.severity("info")("No users found.")
            else
              <.div(
                TableContainer()(
                  Table()(
                    TableHead()(
                      TableRow()(
                        TableCell()("Display Name"),
                        TableCell()("Email"),
                        TableCell()("Active"),
                        TableCell()("Created"),
                        TableCell()("Actions"),
                      ),
                    ),
                    TableBody()(
                      pageItems.map { user =>
                        TableRow.withKey(user.id.value.toString)(
                          TableCell()(user.displayName),
                          TableCell()(user.email),
                          TableCell()(
                            Chip.withProps(
                              ChipOwnProps()
                                .setLabel(if (user.active) "Active" else "Inactive").setColor(
                                  if (user.active) "success" else "default",
                                ).setSize("small").asInstanceOf[Chip.Props],
                            )(),
                          ),
                          TableCell()(user.createdAt.toString.take(19)),
                          TableCell()(
                            Box.withProps(
                              BoxOwnProps[Theme]()
                                .setSx(
                                  js.Dynamic
                                    .literal(display = "flex", gap = 1, flexWrap = "wrap").asInstanceOf[SxProps[Theme]],
                                ).asInstanceOf[Box.Props],
                            )(
                              MuiButton
                                .variant("outlined")
                                .size("small")
                                .onClick(() => openEdit(user).runNow())("Edit"),
                              MuiButton
                                .variant("outlined")
                                .size("small")
                                .onClick(() => openPerms(user).runNow())("Capabilities"),
                              MuiButton
                                .variant("outlined")
                                .size("small")
                                .onClick(() => openRoles(user).runNow())("Roles"),
                              MuiButton
                                .variant("outlined")
                                .size("small")
                                .onClick(() => openIdentities(user).runNow())("Identities"),
                              if (user.active)
                                MuiButton
                                  .variant("outlined")
                                  .size("small")
                                  .color("error")
                                  .onClick(() => handleDeactivate(user).runNow())("Deactivate")
                              else
                                MuiButton
                                  .variant("outlined")
                                  .size("small")
                                  .color("success")
                                  .onClick(() => handleReactivate(user).runNow())("Reactivate"),
                            ),
                          ),
                        )
                      }*,
                    ),
                  ),
                ),
                MuiTablePagination
                  .component("div")
                  .count(state.value.users.size)
                  .page(state.value.page)
                  .rowsPerPage(state.value.rowsPerPage)
                  .rowsPerPageOptions(js.Array(5, 10, 25, 50))
                  .onPageChange(
                    (
                      _,
                      p,
                    ) => state.setState(state.value.copy(page = p)).runNow(),
                  )
                  .onRowsPerPageChange(e =>
                    state
                      .setState(state.value.copy(rowsPerPage = e.target.value.asInstanceOf[String].toInt, page = 0))
                      .runNow(),
                  )(),
              ),
            Dialog(isEditOpen)(
              DialogTitle()("Edit User"),
              DialogContent()(
                MuiTextField
                  .label("Display Name")
                  .value(editDisplayName)
                  .fullWidth(true)
                  .variant("outlined")
                  .onChange(e =>
                    state
                      .setState(
                        state.value.copy(editState = Some(EditState(e.target.value.asInstanceOf[String], editEmail))),
                      )
                      .runNow(),
                  ),
                MuiTextField
                  .label("Email")
                  .value(editEmail)
                  .fullWidth(true)
                  .variant("outlined")
                  .onChange(e =>
                    state
                      .setState(
                        state.value.copy(
                          editState = Some(EditState(editDisplayName, e.target.value.asInstanceOf[String])),
                        ),
                      )
                      .runNow(),
                  ),
              ),
              DialogActions()(
                MuiButton.variant("text").onClick(() => closeEdit().runNow())("Cancel"),
                MuiButton
                  .variant("contained")
                  .disabled(state.value.saving)
                  .onClick(() => saveEdit().runNow())("Save"),
              ),
            ),
            Dialog(state.value.createOpen)(
              DialogTitle()("Create User"),
              DialogContent()(
                MuiTextField
                  .label("Display Name")
                  .value(state.value.createName)
                  .fullWidth(true)
                  .variant("outlined")
                  .onChange(e =>
                    state.setState(state.value.copy(createName = e.target.value.asInstanceOf[String])).runNow(),
                  ),
                MuiTextField
                  .label("Email")
                  .value(state.value.createEmail)
                  .fullWidth(true)
                  .variant("outlined")
                  .onChange(e =>
                    state.setState(state.value.copy(createEmail = e.target.value.asInstanceOf[String])).runNow(),
                  ),
              ),
              DialogActions()(
                MuiButton.variant("text").onClick(() => closeCreate().runNow())("Cancel"),
                MuiButton
                  .variant("contained")
                  .disabled(state.value.saving)
                  .onClick(() => saveCreate().runNow())("Create"),
              ),
            ),
            Dialog(state.value.permsUser.isDefined)(
              DialogTitle()(
                s"Capabilities — ${state.value.permsUser.map(_.displayName).getOrElse("")}",
              ),
              DialogContent()(
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant("subtitle2").setSx(
                      js.Dynamic.literal(mb = 1).asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Typography.Props],
                )("Existing grants:"),
                if (state.value.grants.isEmpty)
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("body2").setSx(
                        js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )(
                    "No capability grants.",
                  )
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
                      state.value.grants.map { g =>
                        TableRow.withKey(g.id.value.toString)(
                          TableCell()(g.capability.value),
                          TableCell()(g.approvalMode.toString),
                          TableCell()(
                            MuiButton
                              .size("small")
                              .color("error")
                              .onClick(() => revokeGrant(g.id).runNow())("Revoke"),
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
                        val alreadyGranted = state.value.grants.exists(_.capability == cap)
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
                MuiButton.variant("text").onClick(() => closePerms().runNow())("Close"),
              ),
            ),
            Dialog(state.value.rolesUser.isDefined)(
              DialogTitle()(
                s"Roles — ${state.value.rolesUser.map(_.displayName).getOrElse("")}",
              ),
              DialogContent()(
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant("subtitle2").setSx(
                      js.Dynamic.literal(mb = 1).asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Typography.Props],
                )("Assigned roles:"),
                if (state.value.userRoles.isEmpty)
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("body2").setSx(
                        js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )(
                    "No roles assigned.",
                  )
                else
                  Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
                    TableHead()(
                      TableRow()(
                        TableCell()("Role"),
                        TableCell()("Actions"),
                      ),
                    ),
                    TableBody()(
                      state.value.userRoles.map { r =>
                        TableRow.withKey(r.id.value.toString)(
                          TableCell()(r.name),
                          TableCell()(
                            MuiButton
                              .size("small")
                              .color("error")
                              .onClick(() => removeRole(r.id).runNow())("Remove"),
                          ),
                        )
                      }*,
                    ),
                  ), {
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(mt = 2, display = "flex", gap = 1, alignItems = "center").asInstanceOf[SxProps[
                            Theme,
                          ]],
                      ).asInstanceOf[Box.Props],
                  )(
                    Typography.withProps(TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props])(
                      "Assign role:",
                    ),
                    MuiSelect
                      .value(state.value.assignRoleId)
                      .size("small")
                      .displayEmpty(true)
                      .onChange { e =>
                        state.setState(state.value.copy(assignRoleId = e.target.asInstanceOf[org.scalajs.dom.html.Select].value)).runNow()
                      }(
                        ((MuiMenuItem.value("")("— Select a role —"): VdomNode) ::
                          state.value.allRoles.map { r =>
                            MuiMenuItem.withKey(r.id.value.toString).value(r.id.value.toString)(r.name): VdomNode
                          })*,
                      ),
                    MuiButton
                      .variant("contained")
                      .size("small")
                      .disabled(state.value.assignRoleId.trim.isEmpty)
                      .onClick(() => assignRole().runNow())("Assign"),
                  )
                },
              ),
              DialogActions()(
                MuiButton.variant("text").onClick(() => closeRoles().runNow())("Close"),
              ),
            ),
            Dialog(state.value.identsUser.isDefined)(
              DialogTitle()(
                s"Channel Identities — ${state.value.identsUser.map(_.displayName).getOrElse("")}",
              ),
              DialogContent()(
                state.value.error.fold(EmptyVdom)(err => Alert.severity("error")(err)),
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant("subtitle2").setSx(
                      js.Dynamic.literal(mb = 1).asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Typography.Props],
                )("Linked identities:"),
                if (state.value.identities.isEmpty)
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("body2").setSx(
                        js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )(
                    "No channel identities.",
                  )
                else
                  Table.withProps(TableOwnProps().setSize("small").asInstanceOf[Table.Props])(
                    TableHead()(
                      TableRow()(
                        TableCell()("Channel"),
                        TableCell()("Channel User ID"),
                        TableCell()("Verified"),
                        TableCell()("Actions"),
                      ),
                    ),
                    TableBody()(
                      state.value.identities.map { ci =>
                        TableRow.withKey(ci.id.value.toString)(
                          TableCell()(ci.channelType.toString),
                          TableCell()(ci.channelUserId),
                          TableCell()(if (ci.verified) "Yes" else "No"),
                          TableCell()(
                            MuiButton
                              .size("small")
                              .color("error")
                              .onClick(() => unlinkIdentity(ci.id).runNow())("Unlink"),
                          ),
                        )
                      }*,
                    ),
                  ), {
                  val chTypeOpts: VdomElement =
                    <.span(
                      ChannelType.values.map { ct =>
                        <.option(^.key := ct.toString, ^.value := ct.toString)(ct.toString)
                      }*,
                    )
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(mt = 2, display = "flex", gap = 1, alignItems = "center").asInstanceOf[SxProps[
                            Theme,
                          ]],
                      ).asInstanceOf[Box.Props],
                  )(
                    <.select(
                      ^.value := state.value.newChType,
                      ^.onChange ==> { (e: ReactEventFromInput) =>
                        state.setState(state.value.copy(newChType = e.target.value))
                      },
                      chTypeOpts,
                    ),
                    MuiTextField
                      .label("Channel User ID")
                      .value(state.value.newChUserId)
                      .size("small")
                      .onChange(e =>
                        state.setState(state.value.copy(newChUserId = e.target.value.asInstanceOf[String])).runNow(),
                      ),
                    MuiButton
                      .variant("contained")
                      .size("small")
                      .disabled(state.value.newChUserId.trim.isEmpty)
                      .onClick(() => linkIdentity().runNow())("Link"),
                  )
                },
              ),
              DialogActions()(
                MuiButton.variant("text").onClick(() => closeIdentities().runNow())("Close"),
              ),
            ),
          )
      }

  def apply(user: User): VdomElement = component(user)

}
