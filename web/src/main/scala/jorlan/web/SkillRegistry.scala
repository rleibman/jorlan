/*
 * Copyright (c) 2026 Roberto Leibman - All Rights Reserved
 *
 * This source code is protected under international copyright law.  All rights
 * reserved and protected by the copyright holders.
 * This file is confidential and only available to authorized individuals with the
 * permission of the copyright holders.  If you encounter this file and do not have
 * permission, please contact the copyright holders and delete this file.
 */

package jorlan.web

import japgolly.scalajs.react.*
import japgolly.scalajs.react.vdom.VdomElement
import org.scalajs.dom
import zio.json.*
import zio.json.ast.Json

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

// Simplified Registration Logic
object SkillRegistry {

  // Host state storing the skill configurations as ZIO JSON AST fragments
  private var currentConfigs = Map[String, Json](
    "weather-skill" -> """{"city": "Paris"}""".fromJson[Json].getOrElse(Json.Null),
  )

//  @JSExportTopLevel("registerRemoteSkill")
//  def registerRemoteSkill(
//                           skillId:    String,
//                           rawPayload: js.Dynamic,
//                         ): Unit = {
//    // ... (Your existing registration step 4) ...
//    val bridgedComponent = JsComponent[js.Object, Children.None, js.Object](rawPayload.component.asInstanceOf[js.Any])
//    loadedSkills.put(skillId, bridgedComponent(js.Dynamic.literal().asInstanceOf[js.Object]))
//
//    // Cache the cleanup hook if the plugin provided one
//    if (!js.isUndefined(rawPayload.onUnload)) {
//      cleanupHooks.put(skillId, rawPayload.onUnload.asInstanceOf[js.Function0[Unit]])
//    }
//  }

  @JSExportTopLevel("registerRemoteSkill")
  def registerRemoteSkill(
    skillId:    String,
    rawPayload: js.Dynamic,
  ): Unit = {
    // 1. Fetch current config and serialize to a plain string
    val targetConfigJson: Json = currentConfigs.getOrElse(skillId, Json.Null)
    val configString:     String = targetConfigJson.toJson

    // 2. Define the callback function. It receives a plain string from the plugin.
    val onSaveCallback: js.Function1[String, Unit] = (updatedString: String) => {
      updatedString.fromJson[Json] match {
        case Right(parsedJson) =>
          currentConfigs = currentConfigs + (skillId -> parsedJson)
          println(s"Saved new ZIO JSON config for $skillId: ${parsedJson.toJson}")
        // Send to your backend database here if needed!
        case Left(error) =>
          println(s"Failed to parse incoming skill config update: $error")
      }
    }

    // 3. Pass the string and callback into the raw JS Props object
    val pluginProps = js.Dynamic.literal(
      "initialConfigStr" -> configString,
      "onSave"           -> onSaveCallback,
    )

    // 4. Bridge the raw JS component into scalajs-react
    val jsComponentRaw = rawPayload.component.asInstanceOf[js.Any]
    val bridgedComponent = JsComponent[js.Object, Children.None, js.Object](jsComponentRaw)

    val renderedElement: VdomElement = bridgedComponent(pluginProps.asInstanceOf[js.Object])

    // Render 'renderedElement' into your main dashboard layout
  }

  // Tracks active rendered components
  private val loadedSkills = mutable.Map[String, VdomElement]()
  // Tracks optional cleanup hooks provided by plugins
  private val cleanupHooks = mutable.Map[String, js.Function0[Unit]]()

  /** Purges all application-level states and triggers plugin-defined cleanups.
    */
  def purgeSkillRecord(skillId: String): Unit = {
    // 1. Fire custom cleanup logic from the plugin if it exists
    cleanupHooks
      .get(skillId).foreach(hook =>
        try
          hook()
        catch {
          case _: Throwable => ()
        },
      )

    // 2. Remove from the local rendering maps
    loadedSkills.remove(skillId)
    cleanupHooks.remove(skillId)
  }

}
