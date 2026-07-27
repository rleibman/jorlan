/*
 * Copyright 2026 Roberto Leibman
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai

import dev.langchain4j.model.chat.request.json.*
import zio.test.*

import scala.jdk.CollectionConverters.*

/** Verifies that [[ToolSupport.buildToolSpecification]] makes optional properties nullable so strict providers (Groq,
  * OpenAI) accept an explicit `null` argument instead of rejecting the whole tool call.
  */
object ToolSupportSpec extends ZIOSpecDefault {

  private val schema =
    """{"type":"object","properties":{
      |  "playerId":{"type":"string","description":"optional player"},
      |  "count":{"type":"integer","description":"optional count"},
      |  "level":{"type":"integer","description":"required level"}
      |},"required":["level"]}""".stripMargin

  override def spec: Spec[TestEnvironment & zio.Scope, Any] =
    suite("ToolSupport")(
      test("optional properties become nullable (anyOf string|null); required stay strict") {
        val spec = ToolSupport.buildToolSpecification(ScalaToolSpec("lyrion.volume", "set volume", schema))
        val params = spec.parameters()
        val props = params.properties().asScala

        val playerId = props("playerId")
        val count = props("count")
        val level = props("level")

        val playerIdNullable = playerId match {
          case a: JsonAnyOfSchema => a.anyOf().asScala.exists(_.isInstanceOf[JsonNullSchema])
          case _ => false
        }
        val countNullable = count match {
          case a: JsonAnyOfSchema => a.anyOf().asScala.exists(_.isInstanceOf[JsonNullSchema])
          case _ => false
        }

        assertTrue(
          playerIdNullable,
          countNullable,
          level.isInstanceOf[JsonIntegerSchema],
          params.required().asScala.toSet == Set("level"),
        )
      },
      // Shape taken from the real `gramps_create_person` MCP tool, whose `primary_name` argument is an object
      // containing an array of objects. Flattening any of that to a string leaves the tool uncallable.
      test("nested object properties are recursed into, not flattened to a string") {
        val nested =
          """{"type":"object","properties":{
            |  "primary_name":{"type":"object","description":"Primary name",
            |    "properties":{
            |      "first_name":{"type":"string"},
            |      "surname_list":{"type":"array","items":{"type":"object",
            |        "properties":{"surname":{"type":"string"},"primary":{"type":"boolean"}},
            |        "required":["surname"]}}
            |    },
            |    "required":["first_name"]}
            |},"required":["primary_name"]}""".stripMargin

        val params = ToolSupport.buildToolSpecification(ScalaToolSpec("gramps.create", "create", nested)).parameters()
        val primaryName = params.properties().asScala("primary_name")

        val nameObj = primaryName match {
          case o: JsonObjectSchema => Some(o)
          case _ => None
        }
        val surnameList = nameObj.flatMap(_.properties().asScala.get("surname_list"))
        // surname_list is optional inside primary_name, so it is wrapped as anyOf(array, null).
        val arraySchema = surnameList.collect { case a: JsonAnyOfSchema =>
          a.anyOf().asScala.collectFirst { case arr: JsonArraySchema => arr }
        }.flatten
        val itemSchema = arraySchema.map(_.items())

        assertTrue(
          nameObj.isDefined,
          nameObj.exists(_.required().asScala.toSet == Set("first_name")),
          arraySchema.isDefined,
          itemSchema.exists(_.isInstanceOf[JsonObjectSchema]),
        )
      },
      test("arrays get an items schema even when the server omits one") {
        val arrays =
          """{"type":"object","properties":{
            |  "handles":{"type":"array","description":"entity handles"},
            |  "tags":{"type":"array","items":{"type":"string"}}
            |},"required":["handles","tags"]}""".stripMargin

        val params = ToolSupport.buildToolSpecification(ScalaToolSpec("gramps.batch", "batch", arrays)).parameters()
        val props = params.properties().asScala

        val handles = props("handles").asInstanceOf[JsonArraySchema]
        val tags = props("tags").asInstanceOf[JsonArraySchema]

        assertTrue(
          handles.items().isInstanceOf[JsonStringSchema],
          tags.items().isInstanceOf[JsonStringSchema],
        )
      },
      // MCP servers express nullable fields as `"type": ["string", "null"]`; treating that union as the literal
      // type name would fall through to a plain string schema and lose the declared type.
      test("union types like [\"string\",\"null\"] resolve to the non-null member") {
        val union =
          """{"type":"object","properties":{
            |  "father_handle":{"type":["string","null"],"description":"father"},
            |  "count":{"type":["integer","null"]}
            |},"required":["father_handle","count"]}""".stripMargin

        val params = ToolSupport.buildToolSpecification(ScalaToolSpec("gramps.update", "update", union)).parameters()
        val props = params.properties().asScala

        assertTrue(
          props("father_handle").isInstanceOf[JsonStringSchema],
          props("count").isInstanceOf[JsonIntegerSchema],
        )
      },
    )

}
