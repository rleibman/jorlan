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
import jorlan.web.AsyncCallbackRepositories
import jorlan.web.components.{MuiButton, MuiMenuItem, MuiSelect, MuiTextField}
import net.leibman.jorlan.muiMaterial.components.*

import scala.language.unsafeNulls
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

object SettingsPage {

  private val availableLanguages: Seq[String] = Seq(
    "English",
    "Spanish",
    "Esperanto",
    "French",
    "German",
    "Portuguese",
    "Japanese",
    "Chinese",
    "Korean",
  )

  private val languageCodeToName: Map[String, String] = Map(
    "en" -> "English",
    "es" -> "Spanish",
    "eo" -> "Esperanto",
    "fr" -> "French",
    "de" -> "German",
    "pt" -> "Portuguese",
    "ja" -> "Japanese",
    "zh" -> "Chinese",
    "ko" -> "Korean",
  )

  private def normalizeLanguages(langs: List[String]): List[String] =
    langs.map(l => languageCodeToName.getOrElse(l, l))

  case class State(
    personality: Option[Personality],
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
            AsyncCallbackRepositories.setting
              .serverPersonality()
              .flatMap { personality =>
                val normalized = personality.map(p => p.copy(languages = normalizeLanguages(p.languages)))
                state.setState(State(normalized, loading = false, saved = false, error = None)).asAsyncCallback
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
                AsyncCallbackRepositories.setting
                  .updatePersonality(p.name, p.formality, p.languages, p.expertise, p.prompt)
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
                      .label("Personality Description (auto-generated, read-only)")
                      .value(
                        if (p.formality == Formality.Custom) ""
                        else Personality.buildSystemPrompt(p.copy(prompt = "")),
                      )
                      .multiline(true)
                      .rows(4)
                      .fullWidth(true)
                      .variant("outlined")
                      .set("slotProps", js.Dynamic.literal(input = js.Dynamic.literal(readOnly = true)))
                      .set("sx", js.Dynamic.literal(backgroundColor = "action.hover")), {
                      val langItems: Seq[VdomElement] = availableLanguages.map { lang =>
                        MuiMenuItem
                          .value(lang)
                          .set(
                            "sx",
                            js.Dynamic.literal(fontWeight = if (p.languages.contains(lang)) "bold" else "normal"),
                          )(lang): VdomElement
                      }
                      FormControl.set("fullWidth", true)(
                        <.label("Languages"),
                        MuiSelect
                          .label("Languages")
                          .set("multiple", true)
                          .set("value", p.languages.toJSArray)
                          .set(
                            "renderValue",
                            (
                              (selected: js.Any) => selected.asInstanceOf[js.Array[String]].toList.mkString(", "),
                            ): js.Function1[js.Any, String],
                          )
                          .onChange(e => {
                            val arr = e.target.value.asInstanceOf[js.Array[String]].toList
                            state
                              .setState(
                                state.value.copy(
                                  personality = Some(p.copy(languages = arr)),
                                  saved = false,
                                ),
                              )
                              .runNow()
                          })(langItems*),
                      )
                    },
                    MuiTextField
                      .label("Expertise (comma-separated)")
                      .value(p.expertise.mkString(", "))
                      .fullWidth(true)
                      .variant("outlined")
                      .placeholder("e.g. Scala, functional programming, distributed systems")
                      .onChange(e => {
                        val raw = e.target.value.asInstanceOf[String]
                        val items = raw.split(",").map(_.trim).filter(_.nonEmpty).toList
                        state
                          .setState(
                            state.value.copy(
                              personality = Some(p.copy(expertise = items)),
                              saved = false,
                            ),
                          )
                          .runNow()
                      }),
                    MuiTextField
                      .label("Additional Personality Notes")
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
