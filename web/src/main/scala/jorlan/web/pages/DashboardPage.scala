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
import net.leibman.jorlan.apexcharts.ApexCharts.ApexOptions
import net.leibman.jorlan.apexcharts.anon.Data
import net.leibman.jorlan.apexcharts.{ApexAxisChartSeries, ApexNonAxisChartSeries}
import net.leibman.jorlan.muiMaterial.components.*
import net.leibman.jorlan.muiMaterial.stylesCreateThemeNoVarsMod.Theme
import net.leibman.jorlan.muiMaterial.typographyTypographyMod.TypographyOwnProps
import net.leibman.jorlan.muiSystem.boxBoxMod.BoxOwnProps
import net.leibman.jorlan.muiSystem.styleFunctionSxStyleFunctionSxMod.SxProps
import net.leibman.jorlan.reactApexcharts.components.ReactApexcharts
import net.leibman.jorlan.reactApexcharts.{reactApexchartsStrings => chartTypes}
import zio.json.*
import zio.json.ast.Json

import scala.language.unsafeNulls
import scala.scalajs.js

object DashboardPage {

  case class State(
    stats:         Option[DashboardStats],
    skills:        scala.collection.immutable.List[SkillInfo],
    skillData:     Map[String, Option[String]],
    loadedModules: Set[String],
    loading:       Boolean,
    error:         Option[String],
  )

  private val cardSx: SxProps[Theme] =
    js.Dynamic
      .literal(
        p = 2,
        border = "1px solid",
        borderColor = "divider",
        borderRadius = 1,
        boxShadow = "0 1px 4px rgba(0,0,0,0.12)",
      ).asInstanceOf[SxProps[Theme]]

  val component =
    ScalaFnComponent
      .withHooks[User]
      .useState(
        State(
          stats = None,
          skills = scala.collection.immutable.List.empty,
          skillData = Map.empty,
          loadedModules = Set.empty,
          loading = true,
          error = None,
        ),
      )
      .useEffectOnMountBy {
        (
          _,
          state,
        ) =>
          Callback {
            AsyncCallbackRepositories
              .dashboardStats()
              .flatMap { statsOpt =>
                AsyncCallbackRepositories.skill
                  .listSkills()
                  .flatMap { skills =>
                    state
                      .setState(state.value.copy(stats = statsOpt, skills = skills, loading = false))
                      .asAsyncCallback
                      .flatMap { _ =>
                        val skillsWithDashboard = skills.filter(_.hasDashboardData)
                        japgolly.scalajs.react.callback.AsyncCallback
                          .traverse(skillsWithDashboard) { skill =>
                            AsyncCallbackRepositories
                              .skillDashboardData(skill.name)
                              .flatMap { dataOpt =>
                                state
                                  .modState(s => s.copy(skillData = s.skillData + (skill.name -> dataOpt)))
                                  .asAsyncCallback
                              }
                          }.void
                      }
                  }
              }
              .completeWith(PageUtils.onError(err => state.setState(state.value.copy(loading = false, error = err))))
              .runNow()
          }
      }
      .render {
        (
          _,
          state,
        ) =>
          val outerSx = js.Dynamic
            .literal(p = 3, display = "flex", flexDirection = "column", gap = 3).asInstanceOf[SxProps[Theme]]

          def summaryCard(
            label: String,
            value: String,
            icon:  String,
          ): VdomElement =
            Box.withProps(BoxOwnProps[Theme]().setSx(cardSx).asInstanceOf[Box.Props])(
              Box.withProps(
                BoxOwnProps[Theme]()
                  .setSx(
                    js.Dynamic.literal(display = "flex", alignItems = "center", gap = 1).asInstanceOf[SxProps[Theme]],
                  )
                  .asInstanceOf[Box.Props],
              )(
                <.span(
                  ^.className := "material-icons",
                  ^.style     := js.Dynamic.literal(fontSize = "20px", color = "#2563eb"),
                )(icon),
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant("body2")
                    .setSx(js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]])
                    .asInstanceOf[Typography.Props],
                )(label),
              ),
              Typography.withProps(
                TypographyOwnProps()
                  .setVariant("h5")
                  .setSx(js.Dynamic.literal(fontWeight = "bold", mt = 0.5).asInstanceOf[SxProps[Theme]])
                  .asInstanceOf[Typography.Props],
              )(value),
            )

          def renderSummaryCards(): VdomElement =
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic
                    .literal(display = "grid", gridTemplateColumns = "repeat(4, 1fr)", gap = 2)
                    .asInstanceOf[SxProps[Theme]],
                ).asInstanceOf[Box.Props],
            )(
              summaryCard(
                "Active Sessions",
                state.value.stats.map(_.activeSessionCount.toString).getOrElse("–"),
                "smart_toy",
              ),
              summaryCard(
                "Events Today",
                state.value.stats.map(_.eventCountToday.toString).getOrElse("–"),
                "event_note",
              ),
              summaryCard(
                "Skill Invocations",
                state.value.stats.map(_.skillInvocationCount.toString).getOrElse("–"),
                "extension",
              ),
              summaryCard(
                "Job Success Rate",
                state.value.stats.map(s => f"${s.schedulerSuccessRate * 100}%.0f%%").getOrElse("–"),
                "schedule",
              ),
            )

          def renderCharts(stats: DashboardStats): VdomElement =
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic.literal(display = "flex", flexDirection = "column", gap = 3).asInstanceOf[SxProps[Theme]],
                )
                .asInstanceOf[Box.Props],
            )(
              Box.withProps(BoxOwnProps[Theme]().setSx(cardSx).asInstanceOf[Box.Props])(
                Typography.withProps(TypographyOwnProps().setVariant("subtitle1").asInstanceOf[Typography.Props])(
                  "Event Volume (last 24 h)",
                ),
                ReactApexcharts
                  .`type`(chartTypes.area)
                  .series(
                    js.Array[Data](
                      Data(
                        js.Array(stats.eventVolumeSeries.map(p => js.Array(p.timestampMs.toDouble, p.count.toDouble))*),
                      )
                        .setName("Events"),
                    ): ApexAxisChartSeries,
                  )
                  .options(
                    js.Dynamic
                      .literal(
                        chart = js.Dynamic.literal(toolbar = js.Dynamic.literal(show = false)),
                        xaxis = js.Dynamic.literal(`type` = "datetime"),
                        dataLabels = js.Dynamic.literal(enabled = false),
                        stroke = js.Dynamic.literal(curve = "smooth"),
                      ).asInstanceOf[ApexOptions],
                  )
                  .height(250)(),
              ),
              Box.withProps(
                BoxOwnProps[Theme]()
                  .setSx(
                    js.Dynamic
                      .literal(display = "grid", gridTemplateColumns = "1fr 1fr 1fr", gap = 2).asInstanceOf[SxProps[
                        Theme,
                      ]],
                  ).asInstanceOf[Box.Props],
              )(
                Box.withProps(BoxOwnProps[Theme]().setSx(cardSx).asInstanceOf[Box.Props])(
                  Typography.withProps(TypographyOwnProps().setVariant("subtitle1").asInstanceOf[Typography.Props])(
                    "Session Status",
                  ),
                  ReactApexcharts
                    .`type`(chartTypes.donut)
                    .series(js.Array[Double](stats.sessionStatusCounts.map(_.count.toDouble)*): ApexNonAxisChartSeries)
                    .options(
                      js.Dynamic
                        .literal(
                          labels = js.Array(stats.sessionStatusCounts.map(_.name)*),
                          legend = js.Dynamic.literal(position = "bottom"),
                          dataLabels = js.Dynamic.literal(enabled = true),
                        ).asInstanceOf[ApexOptions],
                    )
                    .height(250)(),
                ),
                Box.withProps(BoxOwnProps[Theme]().setSx(cardSx).asInstanceOf[Box.Props])(
                  Typography.withProps(TypographyOwnProps().setVariant("subtitle1").asInstanceOf[Typography.Props])(
                    "Skill Invocations",
                  ),
                  ReactApexcharts
                    .`type`(chartTypes.bar)
                    .series(
                      js.Array[Data](
                        Data(js.Array(stats.skillInvocationsByName.map(_.count.toDouble)*))
                          .setName("Invocations"),
                      ): ApexAxisChartSeries,
                    )
                    .options(
                      js.Dynamic
                        .literal(
                          plotOptions = js.Dynamic.literal(bar = js.Dynamic.literal(horizontal = true)),
                          xaxis = js.Dynamic.literal(categories = js.Array(stats.skillInvocationsByName.map(_.name)*)),
                          dataLabels = js.Dynamic.literal(enabled = false),
                        ).asInstanceOf[ApexOptions],
                    )
                    .height(250)(),
                ),
                Box.withProps(BoxOwnProps[Theme]().setSx(cardSx).asInstanceOf[Box.Props])(
                  Typography.withProps(TypographyOwnProps().setVariant("subtitle1").asInstanceOf[Typography.Props])(
                    "Job Outcomes",
                  ),
                  ReactApexcharts
                    .`type`(chartTypes.bar)
                    .series(
                      js.Array[Data](
                        Data(js.Array(stats.jobOutcomeCounts.map(_.count.toDouble)*))
                          .setName("Jobs"),
                      ): ApexAxisChartSeries,
                    )
                    .options(
                      js.Dynamic
                        .literal(
                          xaxis = js.Dynamic.literal(categories = js.Array(stats.jobOutcomeCounts.map(_.name)*)),
                          dataLabels = js.Dynamic.literal(enabled = false),
                          colors = js.Array("#00e396", "#ff4560", "#feb019", "#775dd0"),
                        ).asInstanceOf[ApexOptions],
                    )
                    .height(250)(),
                ),
              ),
            )

          def parseJson(raw: String): Option[Json] =
            Json.decoder.decodeJson(raw).toOption

          def strField(
            j:   Json,
            key: String,
          ): String =
            j match {
              case Json.Obj(fields) => fields.collectFirst { case (`key`, Json.Str(v)) => v }.getOrElse("")
              case _                => ""
            }

          def numField(
            j:   Json,
            key: String,
          ): Double =
            j match {
              case Json.Obj(fields) =>
                fields.collectFirst { case (`key`, Json.Num(v)) => v.doubleValue() }.getOrElse(0.0)
              case _ => 0.0
            }

          def intField(
            j:   Json,
            key: String,
          ): Int =
            j match {
              case Json.Obj(fields) =>
                fields.collectFirst { case (`key`, Json.Num(v)) => v.intValue() }.getOrElse(0)
              case _ => 0
            }

          def arrField(
            j:   Json,
            key: String,
          ): scala.collection.immutable.List[Json] =
            j match {
              case Json.Obj(fields) =>
                fields
                  .collectFirst { case (`key`, Json.Arr(vs)) => vs.toList }.getOrElse(
                    scala.collection.immutable.List.empty,
                  )
              case _ => scala.collection.immutable.List.empty
            }

          def renderMarketWidget(raw: String): VdomElement = {
            val quotes = parseJson(raw).toList.flatMap(j => arrField(j, "quotes"))
            if (quotes.isEmpty)
              Typography.withProps(TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props])(
                "No market data available.",
              )
            else
              Box.withProps(
                BoxOwnProps[Theme]()
                  .setSx(
                    js.Dynamic.literal(display = "flex", flexDirection = "column", gap = 1).asInstanceOf[SxProps[Theme]],
                  )
                  .asInstanceOf[Box.Props],
              )(
                quotes.map { q =>
                  val symbol = strField(q, "symbol")
                  val name = strField(q, "name")
                  val price = strField(q, "price")
                  val change = strField(q, "change")
                  val changePercent = strField(q, "changePercent")
                  val isPositive = !change.startsWith("-")
                  val color = if (isPositive) "#16a34a" else "#dc2626"
                  Box
                    .withProps(
                      BoxOwnProps[Theme]()
                        .setSx(
                          js.Dynamic
                            .literal(
                              display = "flex",
                              justifyContent = "space-between",
                              alignItems = "center",
                              p = 1,
                              borderRadius = 1,
                              background = "rgba(0,0,0,0.03)",
                            ).asInstanceOf[SxProps[Theme]],
                        ).asInstanceOf[Box.Props],
                    ).withKey(symbol)(
                      Box.withProps(BoxOwnProps[Theme]().asInstanceOf[Box.Props])(
                        Typography.withProps(
                          TypographyOwnProps()
                            .setVariant("body2").setSx(
                              js.Dynamic.literal(fontWeight = "bold").asInstanceOf[SxProps[Theme]],
                            ).asInstanceOf[Typography.Props],
                        )(s"$symbol"),
                        Typography.withProps(
                          TypographyOwnProps()
                            .setVariant("caption").setSx(
                              js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                            ).asInstanceOf[Typography.Props],
                        )(name),
                      ),
                      Box.withProps(
                        BoxOwnProps[Theme]()
                          .setSx(js.Dynamic.literal(textAlign = "right").asInstanceOf[SxProps[Theme]]).asInstanceOf[
                            Box.Props,
                          ],
                      )(
                        Typography.withProps(
                          TypographyOwnProps()
                            .setVariant("body2").setSx(
                              js.Dynamic.literal(fontWeight = "bold").asInstanceOf[SxProps[Theme]],
                            ).asInstanceOf[Typography.Props],
                        )(s"$$$price"),
                        Typography.withProps(
                          TypographyOwnProps()
                            .setVariant("caption").setSx(
                              js.Dynamic.literal(color = color).asInstanceOf[SxProps[Theme]],
                            ).asInstanceOf[Typography.Props],
                        )(s"$change ($changePercent)"),
                      ),
                    ).build
                }.toVdomArray,
              )
          }

          def renderWeatherWidget(raw: String): VdomElement =
            parseJson(raw) match {
              case None =>
                Typography.withProps(TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props])(
                  "No weather data available.",
                )
              case Some(j) =>
                val location = strField(j, "location")
                val temp = numField(j, "temperature")
                val feelsLike = numField(j, "feelsLike")
                val humidity = intField(j, "humidity")
                val description = strField(j, "description")
                val windSpeed = numField(j, "windSpeed")
                val units = strField(j, "units")
                val tempUnit = if (units == "imperial") "°F" else "°C"
                Box.withProps(
                  BoxOwnProps[Theme]()
                    .setSx(
                      js.Dynamic
                        .literal(display = "flex", flexDirection = "column", gap = 0.5).asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Box.Props],
                )(
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("h3").setSx(
                        js.Dynamic.literal(fontWeight = "bold").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )(s"${temp.toInt}$tempUnit"),
                  Typography.withProps(TypographyOwnProps().setVariant("subtitle1").asInstanceOf[Typography.Props])(
                    description.capitalize,
                  ),
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("body2").setSx(
                        js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )(s"Feels like ${feelsLike.toInt}$tempUnit · Humidity $humidity% · Wind $windSpeed m/s"),
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("caption").setSx(
                        js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )(location),
                )
            }

          def renderTimeWidget(raw: String): VdomElement =
            parseJson(raw) match {
              case None =>
                Typography
                  .withProps(TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props])("No time data.")
              case Some(j) =>
                val time = strField(j, "time")
                val date = strField(j, "date")
                val timezone = strField(j, "timezone")
                Box.withProps(
                  BoxOwnProps[Theme]()
                    .setSx(
                      js.Dynamic
                        .literal(
                          display = "flex",
                          flexDirection = "column",
                          alignItems = "center",
                          py = 2,
                        ).asInstanceOf[SxProps[Theme]],
                    ).asInstanceOf[Box.Props],
                )(
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("h2").setSx(
                        js.Dynamic.literal(fontWeight = "bold", letterSpacing = 2).asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )(time),
                  Typography
                    .withProps(TypographyOwnProps().setVariant("subtitle1").asInstanceOf[Typography.Props])(date),
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("caption").setSx(
                        js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )(timezone),
                )
            }

          def renderLyrionWidget(raw: String): VdomElement =
            parseJson(raw) match {
              case None =>
                Typography
                  .withProps(TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props])("No Lyrion data.")
              case Some(j) =>
                val players = arrField(j, "players")
                if (players.isEmpty)
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("body2").setSx(
                        js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )("No players found.")
                else
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", flexDirection = "column", gap = 1).asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Box.Props],
                  )(
                    players.map { p =>
                      val name = strField(p, "name")
                      val title = strField(p, "title")
                      val artist = strField(p, "artist")
                      val album = strField(p, "album")
                      val mode = strField(p, "mode")
                      val isPlaying = mode == "play"
                      val statusIcon = if (isPlaying) "play_circle" else "pause_circle"
                      Box
                        .withProps(
                          BoxOwnProps[Theme]()
                            .setSx(
                              js.Dynamic
                                .literal(
                                  display = "flex",
                                  alignItems = "center",
                                  gap = 1,
                                  p = 1,
                                  borderRadius = 1,
                                  background = "rgba(0,0,0,0.03)",
                                ).asInstanceOf[SxProps[Theme]],
                            )
                            .asInstanceOf[Box.Props],
                        ).withKey(name)(
                          <.span(
                            ^.className := "material-icons",
                            ^.style     := js.Dynamic.literal(color = if (isPlaying) "#2563eb" else "#9ca3af"),
                          )(statusIcon),
                          Box.withProps(BoxOwnProps[Theme]().asInstanceOf[Box.Props])(
                            Typography.withProps(
                              TypographyOwnProps()
                                .setVariant("body2").setSx(
                                  js.Dynamic.literal(fontWeight = "bold").asInstanceOf[SxProps[Theme]],
                                ).asInstanceOf[Typography.Props],
                            )(name),
                            if (title.nonEmpty)
                              Typography.withProps(
                                TypographyOwnProps()
                                  .setVariant("caption").setSx(
                                    js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                                  ).asInstanceOf[Typography.Props],
                              )(s"$title — $artist")
                            else
                              Typography.withProps(
                                TypographyOwnProps()
                                  .setVariant("caption").setSx(
                                    js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                                  ).asInstanceOf[Typography.Props],
                              )("Idle"),
                          ),
                        ).build
                    }.toVdomArray,
                  )
            }

          def renderEmailWidget(raw: String): VdomElement =
            parseJson(raw) match {
              case None =>
                Typography
                  .withProps(TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props])("No email data.")
              case Some(j) =>
                val unreadCount = intField(j, "unreadCount")
                val recentMessages = arrField(j, "recentMessages")
                val errorMsg = strField(j, "error")
                if (errorMsg.nonEmpty)
                  Typography.withProps(
                    TypographyOwnProps()
                      .setVariant("body2").setSx(
                        js.Dynamic.literal(color = "error.main").asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Typography.Props],
                  )(errorMsg)
                else
                  Box.withProps(
                    BoxOwnProps[Theme]()
                      .setSx(
                        js.Dynamic
                          .literal(display = "flex", flexDirection = "column", gap = 1).asInstanceOf[SxProps[Theme]],
                      ).asInstanceOf[Box.Props],
                  )(
                    Box.withProps(
                      BoxOwnProps[Theme]()
                        .setSx(
                          js.Dynamic
                            .literal(display = "flex", alignItems = "center", gap = 1, mb = 1).asInstanceOf[SxProps[
                              Theme,
                            ]],
                        ).asInstanceOf[Box.Props],
                    )(
                      <.span(^.className := "material-icons", ^.style := js.Dynamic.literal(color = "#2563eb"))("mail"),
                      Typography.withProps(
                        TypographyOwnProps()
                          .setVariant("h5").setSx(
                            js.Dynamic.literal(fontWeight = "bold").asInstanceOf[SxProps[Theme]],
                          ).asInstanceOf[Typography.Props],
                      )(s"$unreadCount unread"),
                    ),
                    recentMessages
                      .take(3).map { m =>
                        val from = strField(m, "from")
                        val subject = strField(m, "subject")
                        Box
                          .withProps(
                            BoxOwnProps[Theme]()
                              .setSx(
                                js.Dynamic
                                  .literal(p = 1, borderRadius = 1, background = "rgba(0,0,0,0.03)").asInstanceOf[
                                    SxProps[Theme],
                                  ],
                              )
                              .asInstanceOf[Box.Props],
                          ).withKey(from + subject)(
                            Typography.withProps(
                              TypographyOwnProps()
                                .setVariant("body2").setSx(
                                  js.Dynamic.literal(fontWeight = "bold").asInstanceOf[SxProps[Theme]],
                                ).asInstanceOf[Typography.Props],
                            )(subject),
                            Typography.withProps(
                              TypographyOwnProps()
                                .setVariant("caption").setSx(
                                  js.Dynamic.literal(color = "text.secondary").asInstanceOf[SxProps[Theme]],
                                ).asInstanceOf[Typography.Props],
                            )(from),
                          ).build
                      }.toVdomArray,
                  )
            }

          def renderSkillWidget(
            skill: SkillInfo,
            raw:   String,
          ): VdomElement =
            skill.name match {
              case "market"  => renderMarketWidget(raw)
              case "weather" => renderWeatherWidget(raw)
              case "time"    => renderTimeWidget(raw)
              case "lyrion"  => renderLyrionWidget(raw)
              case "email"   => renderEmailWidget(raw)
              case _         =>
                Typography.withProps(TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props])(
                  "Dashboard data available (no widget registered).",
                )
            }

          def renderSkillSection(skill: SkillInfo): VdomElement =
            Box
              .withProps(BoxOwnProps[Theme]().setSx(cardSx).asInstanceOf[Box.Props]).withKey(skill.name)(
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant("subtitle1")
                    .setSx(js.Dynamic.literal(fontWeight = "bold", mb = 1).asInstanceOf[SxProps[Theme]])
                    .asInstanceOf[Typography.Props],
                )(skill.name.capitalize + " Dashboard"),
                state.value.skillData.get(skill.name) match {
                  case None =>
                    CircularProgress.size(24)()
                  case Some(None) =>
                    Typography.withProps(TypographyOwnProps().setVariant("body2").asInstanceOf[Typography.Props])(
                      "No data available.",
                    )
                  case Some(Some(raw)) =>
                    renderSkillWidget(skill, raw)
                },
              )

          val skillsWithDashboard = state.value.skills.filter(_.hasDashboardData)

          def renderSkillSections(): VdomElement =
            if (skillsWithDashboard.isEmpty) <.span()
            else
              Box.withProps(
                BoxOwnProps[Theme]()
                  .setSx(
                    js.Dynamic.literal(display = "flex", flexDirection = "column", gap = 2).asInstanceOf[SxProps[Theme]],
                  ).asInstanceOf[Box.Props],
              )(
                Typography.withProps(
                  TypographyOwnProps()
                    .setVariant("h6")
                    .setSx(js.Dynamic.literal(mt = 1).asInstanceOf[SxProps[Theme]])
                    .asInstanceOf[Typography.Props],
                )("Per-Skill Dashboards"),
                Box.withProps(
                  BoxOwnProps[Theme]()
                    .setSx(
                      js.Dynamic
                        .literal(
                          display = "grid",
                          gridTemplateColumns = "repeat(auto-fill, minmax(320px, 1fr))",
                          gap = 2,
                        ).asInstanceOf[SxProps[Theme]],
                    )
                    .asInstanceOf[Box.Props],
                )(
                  skillsWithDashboard.map(renderSkillSection).toVdomArray,
                ),
              )

          if (state.value.loading) {
            Box.withProps(
              BoxOwnProps[Theme]()
                .setSx(
                  js.Dynamic.literal(display = "flex", justifyContent = "center", p = 4).asInstanceOf[SxProps[Theme]],
                )
                .asInstanceOf[Box.Props],
            )(CircularProgress())
          } else {
            Box.withProps(BoxOwnProps[Theme]().setSx(outerSx).asInstanceOf[Box.Props])(
              state.value.error.fold(<.span(): VdomElement)(err => Alert.severity("error")(err)),
              renderSummaryCards(),
              state.value.stats.fold(<.span(): VdomElement)(renderCharts),
              renderSkillSections(),
            )
          }
      }

  def apply(user: User): VdomElement = component(user)

}
