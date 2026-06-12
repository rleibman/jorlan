/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web.pages

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.html_<^.*
import jorlan.*
import jorlan.web.JorlanWebApp
import jorlan.web.components.{MuiButton, MuiMenuItem, MuiSelect, MuiTextField}
import net.leibman.jorlan.muiMaterial.components.*

import scala.language.unsafeNulls
import scala.scalajs.js
import jorlan.graphql.client.JorlanClient
import jorlan.graphql.client.JorlanClientDecoders.given

object SettingsPage {

  case class State(
    personality: Option[JorlanClient.Personality.PersonalityView],
    loading:     Boolean,
    saved:       Boolean,
    error:       Option[String],
  )

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(State(None, loading = true, saved = false, error = None))
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            JorlanWebApp
              .makeAdapter()
              .asyncCalibanCallWithAuth(
                JorlanClient.Queries.serverPersonality(JorlanClient.Personality.view),
              )
              .flatMap { personality =>
                state.setState(State(personality, loading = false, saved = false, error = None)).asAsyncCallback
              }
              .completeWith {
                case scala.util.Failure(ex) =>
                  state.setState(state.value.copy(loading = false, error = Some(ex.getMessage)))
                case _ => Callback.empty
              }
              .runNow()
          }
      }
      .render {
        (
          _,
          state,
        ) =>
          def update(): Callback =
            Callback {
              state.value.personality.foreach { p =>
                JorlanWebApp
                  .makeAdapter()
                  .asyncCalibanCallWithAuth(
                    JorlanClient.Mutations.updatePersonality(
                      p.name,
                      p.formality,
                      p.languages,
                      p.expertise,
                      p.prompt,
                    )(JorlanClient.Personality.view),
                  )
                  .flatMap { saved =>
                    state.setState(state.value.copy(personality = saved, saved = true, error = None)).asAsyncCallback
                  }
                  .completeWith {
                    case scala.util.Failure(ex) => state.setState(state.value.copy(error = Some(ex.getMessage)))
                    case _                      => Callback.empty
                  }
                  .runNow()
              }
            }

          <.div(
            Typography.set("variant", "h5").set("sx", js.Dynamic.literal(mb = 3))("Settings"),
            state.value.error.fold(EmptyVdom)(err => Alert.set("severity", "error")(err)),
            if (state.value.loading) CircularProgress()
            else
              state.value.personality.fold(<.div("No personality configured")) { p =>
                Stack
                  .set("direction", "column")
                  .set("spacing", 3)(
                    Typography.set("variant", "h6")("Personality"),
                    FormControl.set("fullWidth", true)(
                      <.label("Formality"),
                      MuiSelect
                        .value(p.formality.toString)
                        .label("Formality")
                        .onChange(e =>
                          state
                            .setState(
                              state.value.copy(
                                personality = Some(
                                  p.copy(formality = Formality.valueOf(e.target.value.asInstanceOf[String])),
                                ),
                                saved = false,
                              ),
                            )
                            .runNow(),
                        )(
                          Formality.values.toList.map { f =>
                            MuiMenuItem.value(f.toString)(f.toString)
                          }*,
                        ),
                    ),
                    MuiTextField
                      .label("System Prompt")
                      .value(p.prompt)
                      .multiline(true)
                      .rows(4)
                      .fullWidth(true)
                      .variant("outlined")
                      .onChange(e =>
                        state
                          .setState(
                            state.value.copy(
                              personality = Some(p.copy(prompt = e.target.value.asInstanceOf[String])),
                              saved = false,
                            ),
                          )
                          .runNow(),
                      ),
                    Box.set("sx", js.Dynamic.literal(display = "flex", gap = 2, alignItems = "center"))(
                      MuiButton
                        .variant("contained")
                        .onClick(() => update().runNow())("Save"),
                      if (state.value.saved) Alert.set("severity", "success")("Saved!") else EmptyVdom,
                    ),
                  )
              },
          )
      }

  def apply(user: User): VdomElement = component(user)

}
